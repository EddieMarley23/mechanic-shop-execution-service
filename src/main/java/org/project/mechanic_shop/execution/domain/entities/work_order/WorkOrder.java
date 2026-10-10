package org.project.mechanic_shop.execution.domain.entities.work_order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.project.mechanic_shop.execution.domain.enums.WorkOrderStatusEnum;

/**
 * The shop-floor view of a service order: where it is in the execution queue and what was done.
 */
@Getter
@Setter
@NoArgsConstructor
public class WorkOrder {
	public static final String SYSTEM_ACTOR = "SYSTEM";

	private UUID id;
	private UUID serviceOrderId;
	private UUID sagaId;
	private VehicleSnapshot vehicle;
	private String customerName;
	private String customerComplaint;
	private Integer odometerReading;
	private String assignedMechanicEmail;
	private WorkOrderStatusEnum status;
	private String diagnosis;
	private List<WorkItem> items = new ArrayList<>();
	private Integer estimatedCompletionDays;
	private List<ProgressEntry> history = new ArrayList<>();
	/** When the order entered its current queue. Orders are served first in, first out. */
	private Instant queuedAt;
	private Instant createdAt;
	private Instant updatedAt;

	public static WorkOrder queueForDiagnosis(UUID serviceOrderId, UUID sagaId, Instant now) {
		WorkOrder workOrder = new WorkOrder();
		workOrder.id = UUID.randomUUID();
		workOrder.serviceOrderId = serviceOrderId;
		workOrder.sagaId = sagaId;
		workOrder.createdAt = now;
		workOrder.queuedAt = now;
		workOrder.moveTo(WorkOrderStatusEnum.DIAGNOSIS_QUEUED, now, SYSTEM_ACTOR, "Service order opened.");
		return workOrder;
	}

	public void startDiagnosis(String mechanic, Instant now) {
		assertStatus(WorkOrderStatusEnum.DIAGNOSIS_QUEUED, "start the diagnosis");
		moveTo(WorkOrderStatusEnum.IN_DIAGNOSIS, now, mechanic, null);
	}

	public void completeDiagnosis(String diagnosis, List<WorkItem> items, String mechanic, Instant now) {
		assertStatus(WorkOrderStatusEnum.IN_DIAGNOSIS, "complete the diagnosis");
		if (items == null || items.isEmpty()) {
			throw new IllegalArgumentException("A diagnosis must list at least one service or part.");
		}
		this.diagnosis = diagnosis;
		this.items = new ArrayList<>(items);
		moveTo(WorkOrderStatusEnum.AWAITING_APPROVAL, now, mechanic, null);
	}

	/** The orchestrator refused the diagnosis (e.g. unknown service or part): the mechanic must redo it. */
	public void reopenDiagnosis(String reason, Instant now) {
		assertStatus(WorkOrderStatusEnum.AWAITING_APPROVAL, "reopen the diagnosis");
		moveTo(WorkOrderStatusEnum.IN_DIAGNOSIS, now, SYSTEM_ACTOR, reason);
	}

	public void queueRepair(Integer estimatedCompletionDays, Instant now) {
		assertStatus(WorkOrderStatusEnum.AWAITING_APPROVAL, "queue the repair");
		this.estimatedCompletionDays = estimatedCompletionDays;
		this.queuedAt = now;
		moveTo(WorkOrderStatusEnum.REPAIR_QUEUED, now, SYSTEM_ACTOR, "Budget approved and paid.");
	}

	public void startRepair(String mechanic, Instant now) {
		assertStatus(WorkOrderStatusEnum.REPAIR_QUEUED, "start the repair");
		moveTo(WorkOrderStatusEnum.IN_REPAIR, now, mechanic, null);
	}

	public void completeRepair(String mechanic, Instant now) {
		assertStatus(WorkOrderStatusEnum.IN_REPAIR, "complete the repair");
		moveTo(WorkOrderStatusEnum.COMPLETED, now, mechanic, null);
	}

	public void failRepair(String reason, String mechanic, Instant now) {
		if (this.status != WorkOrderStatusEnum.REPAIR_QUEUED && this.status != WorkOrderStatusEnum.IN_REPAIR) {
			throw new IllegalStateException("Cannot fail the repair of a work order that is " + this.status + ".");
		}
		moveTo(WorkOrderStatusEnum.FAILED, now, mechanic, reason);
	}

	/**
	 * Cancels the work as part of a Saga compensation. Safe to call more than once.
	 *
	 * @return {@code true} when the status actually changed
	 */
	public boolean cancel(String reason, Instant now) {
		if (!this.status.isActive()) return false;
		moveTo(WorkOrderStatusEnum.CANCELED, now, SYSTEM_ACTOR, reason);
		return true;
	}

	private void moveTo(WorkOrderStatusEnum newStatus, Instant now, String by, String note) {
		this.status = newStatus;
		this.updatedAt = now;
		this.history.add(new ProgressEntry(newStatus, now, by, note));
	}

	private void assertStatus(WorkOrderStatusEnum expected, String action) {
		if (this.status != expected) {
			throw new IllegalStateException("Cannot " + action + " of a work order that is " + this.status + ".");
		}
	}
}
