package org.project.mechanic_shop.execution.application.saga;

import java.util.List;
import java.util.UUID;

/** Payload of {@link SagaMessageTypes#DIAGNOSIS_COMPLETED}: what the orchestrator has to price. */
public record DiagnosisCompletedPayload(String diagnosis, List<Labor> labors, List<Part> parts) {
	public record Labor(UUID mechanicServiceExternalId, Integer quantity) {}

	public record Part(UUID partExternalId, Integer quantity) {}
}
