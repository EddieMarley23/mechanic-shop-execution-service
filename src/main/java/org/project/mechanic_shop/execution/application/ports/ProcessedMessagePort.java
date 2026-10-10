package org.project.mechanic_shop.execution.application.ports;

import java.util.UUID;

/** Registry of consumed messages. Makes the consumers idempotent under at-least-once delivery. */
public interface ProcessedMessagePort {
	/**
	 * @return {@code true} when the message was not seen before and is now registered; {@code false}
	 *     when it is a redelivery that must be ignored
	 */
	boolean registerIfNew(UUID messageId, String type);
}
