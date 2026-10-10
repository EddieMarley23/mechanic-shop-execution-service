package org.project.mechanic_shop.execution.presentation.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.project.mechanic_shop.execution.application.services.WorkOrderService;
import org.project.mechanic_shop.execution.domain.dto.ApiResponse;
import org.project.mechanic_shop.execution.domain.dto.DiagnosisDto;
import org.project.mechanic_shop.execution.domain.dto.RepairFailureDto;
import org.project.mechanic_shop.execution.domain.dto.WorkOrderDto;
import org.project.mechanic_shop.execution.domain.dto.WorkOrderShortDto;
import org.project.mechanic_shop.execution.domain.entities.work_order.WorkItem;
import org.project.mechanic_shop.execution.domain.entities.work_order.WorkOrder;
import org.project.mechanic_shop.execution.domain.enums.WorkItemTypeEnum;
import org.project.mechanic_shop.execution.domain.enums.WorkOrderStatusEnum;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/executions")
@RequiredArgsConstructor
@Tag(name = "Executions")
@Slf4j
public class WorkOrderController {

	private final WorkOrderService service;

	private static final String SUCCESS_MESSAGE = "success";

	@Operation(
		summary = "List the execution queue",
		description = "Returns the work orders still in the shop (diagnosis, awaiting approval or repair), oldest in the queue first. Filter by status to see a single queue, e.g. DIAGNOSIS_QUEUED or REPAIR_QUEUED."
	)
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Paginated result")
	@GetMapping
	@PreAuthorize("hasAnyRole('ADMIN', 'RECEPTIONIST', 'MECHANIC')")
	public ResponseEntity<ApiResponse> queue(
		@RequestParam(name = "status", required = false) WorkOrderStatusEnum status,
		@ParameterObject @PageableDefault(size = 10) Pageable pageable
	) {
		log.info("Listing execution queue. Status filter: {}", status);

		Page<WorkOrder> workOrders = service.findQueue(status, pageable);

		return ResponseEntity.ok(
			new ApiResponse(HttpStatus.OK.value(), SUCCESS_MESSAGE, workOrders.map(WorkOrderShortDto::from))
		);
	}

	@Operation(summary = "Find the execution of a service order", description = "Returns the work order with its full progress history.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Work order found")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Work order not found")
	@GetMapping("/{serviceOrderId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'RECEPTIONIST', 'MECHANIC')")
	public ResponseEntity<ApiResponse> findByServiceOrder(@PathVariable UUID serviceOrderId) {
		log.info("Find work order by Service Order: {}", serviceOrderId);

		return ok(SUCCESS_MESSAGE, service.findByServiceOrderId(serviceOrderId));
	}

	@Operation(summary = "Start the diagnosis", description = "Takes the order from the diagnosis queue. The service order moves to DIAGNOSIS.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Diagnosis started")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Work order not found")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Work order is not waiting for diagnosis")
	@PostMapping("/{serviceOrderId}/diagnosis/start")
	@PreAuthorize("hasAnyRole('MECHANIC', 'ADMIN')")
	public ResponseEntity<ApiResponse> startDiagnosis(@PathVariable UUID serviceOrderId, Authentication authentication) {
		log.info("Action triggered: Starting diagnosis for OS {}", serviceOrderId);

		return ok("Diagnosis started.", service.startDiagnosis(serviceOrderId, RequesterResolver.from(authentication)));
	}

	@Operation(
		summary = "Complete the diagnosis",
		description = "Records the mechanic's diagnosis and the services and parts needed. The Saga prices them and sends the budget to the customer."
	)
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Diagnosis completed")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Work order not found")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Work order is not in diagnosis, or no item was listed")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Validation error")
	@PostMapping("/{serviceOrderId}/diagnosis/complete")
	@PreAuthorize("hasAnyRole('MECHANIC', 'ADMIN')")
	public ResponseEntity<ApiResponse> completeDiagnosis(
		@PathVariable UUID serviceOrderId,
		@RequestBody @Valid DiagnosisDto dto,
		Authentication authentication
	) {
		log.info("Action triggered: Completing diagnosis for OS {}", serviceOrderId);

		List<WorkItem> items = new ArrayList<>();
		if (dto.labors() != null) {
			dto.labors().forEach(l -> items.add(new WorkItem(WorkItemTypeEnum.LABOR, l.mechanicServiceExternalId(), l.quantity())));
		}
		if (dto.parts() != null) {
			dto.parts().forEach(p -> items.add(new WorkItem(WorkItemTypeEnum.PART, p.partExternalId(), p.quantity())));
		}

		return ok(
			"Diagnosis completed. The budget will be sent to the customer.",
			service.completeDiagnosis(serviceOrderId, dto.mechanicDiagnosis(), items, RequesterResolver.from(authentication))
		);
	}

	@Operation(summary = "Start the repair", description = "Takes the order from the repair queue. Only possible after the budget was approved and paid.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Repair started")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Work order not found")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Work order is not waiting for repair")
	@PostMapping("/{serviceOrderId}/repair/start")
	@PreAuthorize("hasAnyRole('MECHANIC', 'ADMIN')")
	public ResponseEntity<ApiResponse> startRepair(@PathVariable UUID serviceOrderId, Authentication authentication) {
		log.info("Action triggered: Starting repair for OS {}", serviceOrderId);

		return ok("Repair started.", service.startRepair(serviceOrderId, RequesterResolver.from(authentication)));
	}

	@Operation(summary = "Complete the repair", description = "Finishes the work. The service order moves to COMPLETED and the customer is notified.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Repair completed")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Work order not found")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Work order is not in repair")
	@PostMapping("/{serviceOrderId}/repair/complete")
	@PreAuthorize("hasAnyRole('MECHANIC', 'ADMIN')")
	public ResponseEntity<ApiResponse> completeRepair(@PathVariable UUID serviceOrderId, Authentication authentication) {
		log.info("Action triggered: Completing repair for OS {}", serviceOrderId);

		return ok(
			"Repair completed. The service order will move to COMPLETED.",
			service.completeRepair(serviceOrderId, RequesterResolver.from(authentication))
		);
	}

	@Operation(
		summary = "Report that the repair cannot be carried out",
		description = "Aborts the work. The Saga compensates: the payment is refunded, the reserved stock is returned and the service order is canceled."
	)
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Failure registered")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Work order not found")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Work order is not in the repair stage")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Validation error")
	@PostMapping("/{serviceOrderId}/repair/fail")
	@PreAuthorize("hasAnyRole('MECHANIC', 'ADMIN')")
	public ResponseEntity<ApiResponse> failRepair(
		@PathVariable UUID serviceOrderId,
		@RequestBody @Valid RepairFailureDto dto,
		Authentication authentication
	) {
		log.info("Action triggered: Failing repair for OS {}", serviceOrderId);

		return ok(
			"Repair failure registered. The service order will be compensated and CANCELED.",
			service.failRepair(serviceOrderId, dto.reason(), RequesterResolver.from(authentication))
		);
	}

	private ResponseEntity<ApiResponse> ok(String message, WorkOrder workOrder) {
		return ResponseEntity.ok(new ApiResponse(HttpStatus.OK.value(), message, WorkOrderDto.from(workOrder)));
	}
}
