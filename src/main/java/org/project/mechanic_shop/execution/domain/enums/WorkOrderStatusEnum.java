package org.project.mechanic_shop.execution.domain.enums;

import lombok.Getter;

@Getter
public enum WorkOrderStatusEnum {
	DIAGNOSIS_QUEUED("Diagnosis queued", "Waiting for a mechanic to start the diagnosis."),
	IN_DIAGNOSIS("In diagnosis", "Mechanic is evaluating the vehicle."),
	AWAITING_APPROVAL("Awaiting approval", "Diagnosis delivered. Waiting for budget approval and payment."),
	REPAIR_QUEUED("Repair queued", "Approved and paid. Waiting for a mechanic to start the repair."),
	IN_REPAIR("In repair", "Mechanic is working on the vehicle."),
	COMPLETED("Completed", "Repair finished."),
	FAILED("Failed", "Repair could not be carried out."),
	CANCELED("Canceled", "Work canceled by the service order Saga.");

	private final String label;
	private final String description;

	WorkOrderStatusEnum(String label, String description) {
		this.label = label;
		this.description = description;
	}

	/** Whether the work order still occupies a place in the shop's queue. */
	public boolean isActive() {
		return this != COMPLETED && this != FAILED && this != CANCELED;
	}
}
