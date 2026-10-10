package org.project.mechanic_shop.execution.application.saga;

/** Message types this participant consumes (commands) and produces (events). */
public final class SagaMessageTypes {

	// Commands sent by the orchestrator
	public static final String DIAGNOSIS_START = "execution.diagnosis.start";
	public static final String DIAGNOSIS_REOPEN = "execution.diagnosis.reopen";
	public static final String REPAIR_START = "execution.repair.start";
	public static final String WORK_ORDER_CANCEL = "execution.workorder.cancel";

	// Events sent back to the orchestrator
	public static final String DIAGNOSIS_STARTED = "execution.diagnosis.started";
	public static final String DIAGNOSIS_COMPLETED = "execution.diagnosis.completed";
	public static final String REPAIR_STARTED = "execution.repair.started";
	public static final String REPAIR_COMPLETED = "execution.repair.completed";
	public static final String REPAIR_FAILED = "execution.repair.failed";

	private SagaMessageTypes() {}
}
