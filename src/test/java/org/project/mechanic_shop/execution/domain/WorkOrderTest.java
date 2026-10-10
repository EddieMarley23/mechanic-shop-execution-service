package org.project.mechanic_shop.execution.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.project.mechanic_shop.execution.domain.entities.work_order.WorkItem;
import org.project.mechanic_shop.execution.domain.entities.work_order.WorkOrder;
import org.project.mechanic_shop.execution.domain.enums.WorkItemTypeEnum;
import org.project.mechanic_shop.execution.domain.enums.WorkOrderStatusEnum;

class WorkOrderTest {

	private static final Instant NOW = Instant.parse("2026-01-10T12:00:00Z");
	private static final String MECHANIC = "mecanico@teste.com";
	private static final List<WorkItem> ITEMS = List.of(new WorkItem(WorkItemTypeEnum.LABOR, UUID.randomUUID(), 1));

	private WorkOrder queued() {
		return WorkOrder.queueForDiagnosis(UUID.randomUUID(), UUID.randomUUID(), NOW);
	}

	private WorkOrder awaitingApproval() {
		WorkOrder workOrder = queued();
		workOrder.startDiagnosis(MECHANIC, NOW);
		workOrder.completeDiagnosis("Diagnostico", ITEMS, MECHANIC, NOW);
		return workOrder;
	}

	private WorkOrder inRepair() {
		WorkOrder workOrder = awaitingApproval();
		workOrder.queueRepair(2, NOW);
		workOrder.startRepair(MECHANIC, NOW);
		return workOrder;
	}

	@Test
	void shouldEnterTheDiagnosisQueueWhenCreated() {
		WorkOrder workOrder = queued();

		assertThat(workOrder.getId()).isNotNull();
		assertThat(workOrder.getStatus()).isEqualTo(WorkOrderStatusEnum.DIAGNOSIS_QUEUED);
		assertThat(workOrder.getQueuedAt()).isEqualTo(NOW);
		assertThat(workOrder.getHistory())
			.singleElement()
			.satisfies(entry -> {
				assertThat(entry.status()).isEqualTo(WorkOrderStatusEnum.DIAGNOSIS_QUEUED);
				assertThat(entry.by()).isEqualTo(WorkOrder.SYSTEM_ACTOR);
			});
	}

	@Test
	void shouldRecordWhoDidEachStep() {
		WorkOrder workOrder = queued();

		workOrder.startDiagnosis(MECHANIC, NOW.plusSeconds(60));

		assertThat(workOrder.getStatus()).isEqualTo(WorkOrderStatusEnum.IN_DIAGNOSIS);
		assertThat(workOrder.getUpdatedAt()).isEqualTo(NOW.plusSeconds(60));
		assertThat(workOrder.getHistory().getLast().by()).isEqualTo(MECHANIC);
	}

	@Test
	void shouldKeepTheDiagnosisAndItsItems() {
		WorkOrder workOrder = awaitingApproval();

		assertThat(workOrder.getStatus()).isEqualTo(WorkOrderStatusEnum.AWAITING_APPROVAL);
		assertThat(workOrder.getDiagnosis()).isEqualTo("Diagnostico");
		assertThat(workOrder.getItems()).containsExactlyElementsOf(ITEMS);
	}

	@Test
	void shouldRequireAtLeastOneItemInTheDiagnosis() {
		WorkOrder workOrder = queued();
		workOrder.startDiagnosis(MECHANIC, NOW);

		assertThatThrownBy(() -> workOrder.completeDiagnosis("x", List.of(), MECHANIC, NOW)).isInstanceOf(
			IllegalArgumentException.class
		);
		assertThatThrownBy(() -> workOrder.completeDiagnosis("x", null, MECHANIC, NOW)).isInstanceOf(
			IllegalArgumentException.class
		);
		assertThat(workOrder.getStatus()).isEqualTo(WorkOrderStatusEnum.IN_DIAGNOSIS);
	}

	@Test
	void shouldEnforceTheOrderOfTheSteps() {
		WorkOrder queued = queued();

		assertThatThrownBy(() -> queued.completeDiagnosis("x", ITEMS, MECHANIC, NOW))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("DIAGNOSIS_QUEUED");
		assertThatThrownBy(() -> queued.reopenDiagnosis("x", NOW)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> queued.queueRepair(1, NOW)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> queued.startRepair(MECHANIC, NOW)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> queued.completeRepair(MECHANIC, NOW)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> queued.failRepair("x", MECHANIC, NOW)).isInstanceOf(IllegalStateException.class);

		WorkOrder inDiagnosis = queued();
		inDiagnosis.startDiagnosis(MECHANIC, NOW);
		assertThatThrownBy(() -> inDiagnosis.startDiagnosis(MECHANIC, NOW)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void shouldGoBackToDiagnosisWhenReopened() {
		WorkOrder workOrder = awaitingApproval();

		workOrder.reopenDiagnosis("Part not found", NOW);

		assertThat(workOrder.getStatus()).isEqualTo(WorkOrderStatusEnum.IN_DIAGNOSIS);
		assertThat(workOrder.getHistory().getLast().note()).isEqualTo("Part not found");
	}

	@Test
	void shouldReenterTheQueueWhenTheRepairIsReleased() {
		WorkOrder workOrder = awaitingApproval();

		workOrder.queueRepair(3, NOW.plusSeconds(3600));

		assertThat(workOrder.getStatus()).isEqualTo(WorkOrderStatusEnum.REPAIR_QUEUED);
		assertThat(workOrder.getEstimatedCompletionDays()).isEqualTo(3);
		assertThat(workOrder.getQueuedAt()).isEqualTo(NOW.plusSeconds(3600));
	}

	@Test
	void shouldCompleteTheRepair() {
		WorkOrder workOrder = inRepair();

		workOrder.completeRepair(MECHANIC, NOW);

		assertThat(workOrder.getStatus()).isEqualTo(WorkOrderStatusEnum.COMPLETED);
		assertThat(workOrder.getStatus().isActive()).isFalse();
	}

	@Test
	void shouldFailTheRepairWhileQueuedOrInProgress() {
		WorkOrder inRepair = inRepair();
		WorkOrder repairQueued = awaitingApproval();
		repairQueued.queueRepair(1, NOW);

		inRepair.failRepair("Inviavel", MECHANIC, NOW);
		repairQueued.failRepair("Sem ferramenta", MECHANIC, NOW);

		assertThat(inRepair.getStatus()).isEqualTo(WorkOrderStatusEnum.FAILED);
		assertThat(inRepair.getHistory().getLast().note()).isEqualTo("Inviavel");
		assertThat(repairQueued.getStatus()).isEqualTo(WorkOrderStatusEnum.FAILED);
	}

	@Test
	void shouldCancelOnlyWhileActive() {
		WorkOrder active = awaitingApproval();
		WorkOrder completed = inRepair();
		completed.completeRepair(MECHANIC, NOW);

		assertThat(active.cancel("Orcamento recusado", NOW)).isTrue();
		assertThat(active.cancel("de novo", NOW)).isFalse();
		assertThat(completed.cancel("x", NOW)).isFalse();

		assertThat(active.getStatus()).isEqualTo(WorkOrderStatusEnum.CANCELED);
		assertThat(completed.getStatus()).isEqualTo(WorkOrderStatusEnum.COMPLETED);
	}

	@Test
	void shouldDescribeEveryStatus() {
		for (WorkOrderStatusEnum status : WorkOrderStatusEnum.values()) {
			assertThat(status.getLabel()).isNotBlank();
			assertThat(status.getDescription()).isNotBlank();
		}
	}
}
