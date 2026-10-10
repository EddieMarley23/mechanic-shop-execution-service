package org.project.mechanic_shop.execution.application.saga;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Envelope shared by every Saga command and event. The same shape is declared in the OS Service
 * (orchestrator) and in the Billing Service: the three repositories are independent, so the
 * contract is duplicated on purpose instead of being shared through a library.
 */
public record SagaMessage(
	UUID messageId,
	UUID sagaId,
	UUID serviceOrderId,
	String type,
	Instant occurredAt,
	String correlationId,
	JsonNode payload
) {}
