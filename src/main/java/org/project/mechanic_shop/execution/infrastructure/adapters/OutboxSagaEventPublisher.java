package org.project.mechanic_shop.execution.infrastructure.adapters;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.project.mechanic_shop.execution.application.ports.SagaEventPublisher;
import org.project.mechanic_shop.execution.application.saga.SagaMessage;
import org.project.mechanic_shop.execution.infrastructure.messaging.RabbitTopologyConfig;
import org.project.mechanic_shop.execution.infrastructure.mongo.documents.OutboxMessageDocument;
import org.project.mechanic_shop.execution.shared.config.observability.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Transactional outbox: the event is stored in the caller's MongoDB transaction and relayed to
 * RabbitMQ later by {@code OutboxRelay}.
 */
@Component
@RequiredArgsConstructor
public class OutboxSagaEventPublisher implements SagaEventPublisher {

	private final MongoTemplate mongoTemplate;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void publish(UUID sagaId, UUID serviceOrderId, String type, Object payload) {
		Instant now = Instant.now(clock);

		SagaMessage message = new SagaMessage(
			UUID.randomUUID(),
			sagaId,
			serviceOrderId,
			type,
			now,
			MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY),
			objectMapper.valueToTree(payload)
		);

		OutboxMessageDocument outbox = new OutboxMessageDocument();
		outbox.setId(message.messageId().toString());
		outbox.setExchange(RabbitTopologyConfig.EVENTS_EXCHANGE);
		outbox.setRoutingKey(type);
		outbox.setMessageType(type);
		outbox.setPayload(objectMapper.writeValueAsString(message));
		outbox.setCreatedAt(now);

		mongoTemplate.insert(outbox);
	}
}
