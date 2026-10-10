package org.project.mechanic_shop.execution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.project.mechanic_shop.execution.application.saga.SagaMessage;
import org.project.mechanic_shop.execution.application.saga.SagaMessageTypes;
import org.project.mechanic_shop.execution.application.saga.StartRepairPayload;
import org.project.mechanic_shop.execution.domain.entities.work_order.WorkOrder;
import org.project.mechanic_shop.execution.domain.enums.WorkOrderStatusEnum;
import org.project.mechanic_shop.execution.shared.exception.ResourceNotFoundException;
import org.project.mechanic_shop.execution.support.ExecutionTestHarness;
import org.springframework.data.domain.PageRequest;

/** Edge cases of the execution use cases. The main flows are specified in Gherkin. */
class WorkOrderServiceTest {

	private ExecutionTestHarness harness;

	@BeforeEach
	void setup() {
		harness = new ExecutionTestHarness();
	}

	@Test
	void shouldKeepACopyOfTheOrderDataReceivedFromTheOrchestrator() {
		harness.queueDiagnosis();

		WorkOrder workOrder = harness.workOrder();
		assertThat(workOrder.getSagaId()).isEqualTo(harness.sagaId);
		assertThat(workOrder.getVehicle().licensePlate()).isEqualTo("ABC1D23");
		assertThat(workOrder.getVehicle().brand()).isEqualTo("Volkswagen");
		assertThat(workOrder.getCustomerName()).isEqualTo("Cliente Teste");
		assertThat(workOrder.getCustomerComplaint()).isEqualTo("Ruido ao frear");
		assertThat(workOrder.getOdometerReading()).isEqualTo(45210);
		assertThat(workOrder.getAssignedMechanicEmail()).isEqualTo("mecanico@teste.com");
		assertThat(workOrder.getCreatedAt()).isEqualTo(harness.clock.instant());
	}

	@Test
	void shouldNotPublishAnythingWhenOnlyQueueing() {
		harness.queueDiagnosis();

		assertThat(harness.sagaEvents).isEmpty();
	}

	@Test
	void shouldFailForAnUnknownServiceOrder() {
		UUID unknown = UUID.randomUUID();

		assertThatThrownBy(() -> harness.workOrderService.findByServiceOrderId(unknown)).isInstanceOf(
			ResourceNotFoundException.class
		);
		assertThatThrownBy(() -> harness.workOrderService.startDiagnosis(unknown, ExecutionTestHarness.MECHANIC)).isInstanceOf(
			ResourceNotFoundException.class
		);
		assertThatThrownBy(() -> harness.workOrderService.queueRepair(unknown, 1)).isInstanceOf(
			ResourceNotFoundException.class
		);
	}

	@Test
	void shouldNotPublishWhenAStepIsRefused() {
		harness.queueDiagnosis();

		assertThatThrownBy(() -> harness.workOrderService.completeRepair(harness.serviceOrderId, ExecutionTestHarness.MECHANIC))
			.isInstanceOf(IllegalStateException.class);

		assertThat(harness.sagaEvents).isEmpty();
	}

	@Test
	void shouldTellTheSagaWhoStartedTheRepair() {
		harness.queueDiagnosis();
		harness.workOrderService.startDiagnosis(harness.serviceOrderId, ExecutionTestHarness.MECHANIC);
		harness.workOrderService.completeDiagnosis(harness.serviceOrderId, "x", harness.diagnosisItems(), ExecutionTestHarness.MECHANIC);
		harness.receive(SagaMessageTypes.REPAIR_START, new StartRepairPayload(2));

		harness.workOrderService.startRepair(harness.serviceOrderId, ExecutionTestHarness.MECHANIC);

		assertThat(harness.lastEvent(SagaMessageTypes.REPAIR_STARTED).payload()).containsEntry("mechanic", "mecanico@teste.com");
		assertThat(harness.workOrder().getEstimatedCompletionDays()).isEqualTo(2);
	}

	@Test
	void shouldListEveryActiveOrderWhenNoStatusIsGiven() {
		harness.queueDiagnosis();
		UUID second = harness.queueAnotherOrder("BBB2B22");
		harness.workOrderService.startDiagnosis(second, ExecutionTestHarness.MECHANIC);
		UUID canceled = harness.queueAnotherOrder("CCC3C33");
		harness.workOrderService.cancel(canceled, "x");

		var all = harness.workOrderService.findQueue(null, PageRequest.of(0, 10));
		var inDiagnosis = harness.workOrderService.findQueue(WorkOrderStatusEnum.IN_DIAGNOSIS, PageRequest.of(0, 10));

		assertThat(all.getContent()).extracting(w -> w.getVehicle().licensePlate()).containsExactly("ABC1D23", "BBB2B22");
		assertThat(inDiagnosis.getContent()).extracting(w -> w.getVehicle().licensePlate()).containsExactly("BBB2B22");
	}

	@Test
	void shouldIgnoreTheCancellationOfAnUnknownOrAlreadyClosedOrder() {
		harness.queueDiagnosis();
		harness.workOrderService.cancel(harness.serviceOrderId, "first");
		int historySize = harness.workOrder().getHistory().size();

		harness.workOrderService.cancel(harness.serviceOrderId, "second");
		harness.workOrderService.cancel(UUID.randomUUID(), "unknown");

		assertThat(harness.workOrder().getHistory()).hasSize(historySize);
	}

	@Test
	void shouldCancelWithoutAReason() {
		harness.queueDiagnosis();

		harness.commandHandler.handle(
			new SagaMessage(
				UUID.randomUUID(),
				harness.sagaId,
				harness.serviceOrderId,
				SagaMessageTypes.WORK_ORDER_CANCEL,
				harness.clock.instant(),
				null,
				null
			)
		);

		assertThat(harness.workOrder().getStatus()).isEqualTo(WorkOrderStatusEnum.CANCELED);
	}

	@Test
	void shouldIgnoreARedeliveredCommandAndUnknownTypes() {
		harness.queueDiagnosis();
		harness.workOrderService.startDiagnosis(harness.serviceOrderId, ExecutionTestHarness.MECHANIC);
		harness.workOrderService.completeDiagnosis(harness.serviceOrderId, "x", harness.diagnosisItems(), ExecutionTestHarness.MECHANIC);
		SagaMessage startRepair = harness.command(harness.serviceOrderId, SagaMessageTypes.REPAIR_START, new StartRepairPayload(1));

		harness.commandHandler.handle(startRepair);
		// A second delivery would fail the state check if it were processed again.
		harness.commandHandler.handle(startRepair);
		harness.receive("execution.something.new", Map.of());

		assertThat(harness.workOrder().getStatus()).isEqualTo(WorkOrderStatusEnum.REPAIR_QUEUED);
	}
}
