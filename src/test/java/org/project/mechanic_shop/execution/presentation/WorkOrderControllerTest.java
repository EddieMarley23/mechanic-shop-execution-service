package org.project.mechanic_shop.execution.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.project.mechanic_shop.execution.application.services.Requester;
import org.project.mechanic_shop.execution.application.services.WorkOrderService;
import org.project.mechanic_shop.execution.domain.entities.work_order.VehicleSnapshot;
import org.project.mechanic_shop.execution.domain.entities.work_order.WorkItem;
import org.project.mechanic_shop.execution.domain.entities.work_order.WorkOrder;
import org.project.mechanic_shop.execution.domain.enums.WorkItemTypeEnum;
import org.project.mechanic_shop.execution.domain.enums.WorkOrderStatusEnum;
import org.project.mechanic_shop.execution.presentation.controllers.WorkOrderController;
import org.project.mechanic_shop.execution.presentation.middlewares.GlobalExceptionHandler;
import org.project.mechanic_shop.execution.shared.exception.ResourceNotFoundException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The controller with its exception handler, without the security filter chain: what is checked
 * here is the HTTP contract and that the caller's identity reaches the use cases.
 */
class WorkOrderControllerTest {

	private static final UUID SERVICE_ORDER_ID = UUID.randomUUID();
	private static final Requester MECHANIC = new Requester("mecanico@teste.com", "MECHANIC");
	private static final Authentication MECHANIC_AUTH = new UsernamePasswordAuthenticationToken(
		"mecanico@teste.com",
		null,
		List.of(new SimpleGrantedAuthority("ROLE_MECHANIC"))
	);

	private final WorkOrderService service = mock(WorkOrderService.class);
	private MockMvc mockMvc;

	@BeforeEach
	void setup() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(new WorkOrderController(service))
			.setControllerAdvice(new GlobalExceptionHandler())
			.setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
			.build();
	}

	private WorkOrder workOrder(WorkOrderStatusEnum status) {
		WorkOrder workOrder = WorkOrder.queueForDiagnosis(SERVICE_ORDER_ID, UUID.randomUUID(), Instant.parse("2026-01-10T12:00:00Z"));
		workOrder.setVehicle(new VehicleSnapshot("ABC1D23", "Volkswagen", "Gol", 2021));
		workOrder.setCustomerComplaint("Ruido ao frear");
		workOrder.setStatus(status);
		return workOrder;
	}

	@Test
	void shouldListTheQueueFilteredByStatus() throws Exception {
		when(service.findQueue(eq(WorkOrderStatusEnum.DIAGNOSIS_QUEUED), any(Pageable.class))).thenReturn(
			new PageImpl<>(List.of(workOrder(WorkOrderStatusEnum.DIAGNOSIS_QUEUED)), PageRequest.of(0, 10), 1)
		);

		mockMvc
			.perform(get("/api/executions").param("status", "DIAGNOSIS_QUEUED"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.message").value("success"))
			.andExpect(jsonPath("$.data.content[0].licensePlate").value("ABC1D23"))
			.andExpect(jsonPath("$.data.content[0].status").value("DIAGNOSIS_QUEUED"))
			.andExpect(jsonPath("$.data.content[0].serviceOrderId").value(SERVICE_ORDER_ID.toString()));
	}

	@Test
	void shouldListEveryActiveOrderWithoutFilter() throws Exception {
		when(service.findQueue(eq(null), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

		mockMvc.perform(get("/api/executions")).andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty());
	}

	@Test
	void shouldRejectAnUnknownStatusFilter() throws Exception {
		mockMvc.perform(get("/api/executions").param("status", "NOPE")).andExpect(status().isBadRequest());
	}

	@Test
	void shouldReturnTheWorkOrderWithItsHistory() throws Exception {
		when(service.findByServiceOrderId(SERVICE_ORDER_ID)).thenReturn(workOrder(WorkOrderStatusEnum.DIAGNOSIS_QUEUED));

		mockMvc
			.perform(get("/api/executions/{id}", SERVICE_ORDER_ID))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.vehicle.licensePlate").value("ABC1D23"))
			.andExpect(jsonPath("$.data.history[0].status").value("DIAGNOSIS_QUEUED"))
			.andExpect(jsonPath("$.data.history[0].by").value("SYSTEM"));
	}

	@Test
	void shouldReturnNotFoundForAnUnknownServiceOrder() throws Exception {
		when(service.findByServiceOrderId(SERVICE_ORDER_ID)).thenThrow(new ResourceNotFoundException("Work order not found"));

		mockMvc
			.perform(get("/api/executions/{id}", SERVICE_ORDER_ID))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.message").value("Work order not found"));
	}

	@Test
	void shouldStartTheDiagnosisAsTheAuthenticatedMechanic() throws Exception {
		when(service.startDiagnosis(SERVICE_ORDER_ID, MECHANIC)).thenReturn(workOrder(WorkOrderStatusEnum.IN_DIAGNOSIS));

		mockMvc
			.perform(post("/api/executions/{id}/diagnosis/start", SERVICE_ORDER_ID).principal(MECHANIC_AUTH))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("IN_DIAGNOSIS"));

		verify(service).startDiagnosis(SERVICE_ORDER_ID, MECHANIC);
	}

	@Test
	void shouldCompleteTheDiagnosisWithLaborsAndParts() throws Exception {
		UUID laborId = UUID.randomUUID();
		UUID partId = UUID.randomUUID();
		when(service.completeDiagnosis(eq(SERVICE_ORDER_ID), any(), any(), eq(MECHANIC))).thenReturn(
			workOrder(WorkOrderStatusEnum.AWAITING_APPROVAL)
		);

		mockMvc
			.perform(
				post("/api/executions/{id}/diagnosis/complete", SERVICE_ORDER_ID)
					.principal(MECHANIC_AUTH)
					.contentType(MediaType.APPLICATION_JSON)
					.content(
						"""
						{
						  "mechanicDiagnosis": "Pastilhas desgastadas",
						  "labors": [{"mechanicServiceExternalId": "%s", "quantity": 1}],
						  "parts": [{"partExternalId": "%s", "quantity": 2}]
						}
						""".formatted(laborId, partId)
					)
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.message").value(containsString("budget")));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<WorkItem>> items = ArgumentCaptor.forClass(List.class);
		verify(service).completeDiagnosis(eq(SERVICE_ORDER_ID), eq("Pastilhas desgastadas"), items.capture(), eq(MECHANIC));
		assertThat(items.getValue()).containsExactly(
			new WorkItem(WorkItemTypeEnum.LABOR, laborId, 1),
			new WorkItem(WorkItemTypeEnum.PART, partId, 2)
		);
	}

	@Test
	void shouldValidateTheDiagnosisPayload() throws Exception {
		mockMvc
			.perform(
				post("/api/executions/{id}/diagnosis/complete", SERVICE_ORDER_ID)
					.principal(MECHANIC_AUTH)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"mechanicDiagnosis\": \"\", \"labors\": [{\"quantity\": 0}]}")
			)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.errors").isArray());

		verify(service, never()).completeDiagnosis(any(), any(), any(), any());
	}

	@Test
	void shouldReturnConflictWhenTheDiagnosisListsNoItem() throws Exception {
		when(service.completeDiagnosis(any(), any(), any(), any())).thenThrow(
			new IllegalArgumentException("A diagnosis must list at least one service or part.")
		);

		mockMvc
			.perform(
				post("/api/executions/{id}/diagnosis/complete", SERVICE_ORDER_ID)
					.principal(MECHANIC_AUTH)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"mechanicDiagnosis\": \"x\"}")
			)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message").value("A diagnosis must list at least one service or part."));
	}

	@Test
	void shouldStartAndCompleteTheRepair() throws Exception {
		when(service.startRepair(SERVICE_ORDER_ID, MECHANIC)).thenReturn(workOrder(WorkOrderStatusEnum.IN_REPAIR));
		when(service.completeRepair(SERVICE_ORDER_ID, MECHANIC)).thenReturn(workOrder(WorkOrderStatusEnum.COMPLETED));

		mockMvc
			.perform(post("/api/executions/{id}/repair/start", SERVICE_ORDER_ID).principal(MECHANIC_AUTH))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("IN_REPAIR"));
		mockMvc
			.perform(post("/api/executions/{id}/repair/complete", SERVICE_ORDER_ID).principal(MECHANIC_AUTH))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("COMPLETED"));
	}

	@Test
	void shouldReturnConflictWhenTheRepairWasNotReleasedYet() throws Exception {
		when(service.startRepair(SERVICE_ORDER_ID, MECHANIC)).thenThrow(
			new IllegalStateException("Cannot start the repair of a work order that is AWAITING_APPROVAL.")
		);

		mockMvc
			.perform(post("/api/executions/{id}/repair/start", SERVICE_ORDER_ID).principal(MECHANIC_AUTH))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.status").value(409));
	}

	@Test
	void shouldRegisterARepairFailureWithItsReason() throws Exception {
		when(service.failRepair(SERVICE_ORDER_ID, "Disco trincado", MECHANIC)).thenReturn(workOrder(WorkOrderStatusEnum.FAILED));

		mockMvc
			.perform(
				post("/api/executions/{id}/repair/fail", SERVICE_ORDER_ID)
					.principal(MECHANIC_AUTH)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"reason\": \"Disco trincado\"}")
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("FAILED"));
	}

	@Test
	void shouldRequireAReasonForTheRepairFailure() throws Exception {
		mockMvc
			.perform(
				post("/api/executions/{id}/repair/fail", SERVICE_ORDER_ID)
					.principal(MECHANIC_AUTH)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"reason\": \" \"}")
			)
			.andExpect(status().isUnprocessableContent());

		verify(service, never()).failRepair(any(), any(), any());
	}

	@Test
	void shouldMapAccessDeniedAndUnexpectedErrors() throws Exception {
		when(service.findByServiceOrderId(SERVICE_ORDER_ID))
			.thenThrow(new AccessDeniedException("nope"))
			.thenThrow(new RuntimeException("mongo password is hunter2"));

		mockMvc.perform(get("/api/executions/{id}", SERVICE_ORDER_ID)).andExpect(status().isForbidden());
		mockMvc
			.perform(get("/api/executions/{id}", SERVICE_ORDER_ID))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.message").value("Unexpected error: Contact the technical team."));
	}
}
