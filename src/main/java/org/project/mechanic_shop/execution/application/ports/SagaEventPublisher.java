package org.project.mechanic_shop.execution.application.ports;

import java.util.UUID;

/**
 * Publishes the replies of this participant to the Saga orchestrator.
 *
 * <p>Implementations must take part in the caller's transaction (transactional outbox), so a reply
 * is never sent for a state change that was rolled back, and never lost for one that was committed.
 */
public interface SagaEventPublisher {
	void publish(UUID sagaId, UUID serviceOrderId, String type, Object payload);
}
