package org.project.mechanic_shop.execution.infrastructure.messaging;

import com.newrelic.api.agent.NewRelic;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.project.mechanic_shop.execution.application.saga.SagaCommandHandler;
import org.project.mechanic_shop.execution.application.saga.SagaMessage;
import org.project.mechanic_shop.execution.shared.config.observability.CorrelationIdFilter;
import org.project.mechanic_shop.execution.shared.config.observability.ObservabilityReporter;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Entry point of the Saga commands. A failure is rethrown so the container retries the delivery
 * and, once the attempts are exhausted, rejects the message to the dead-letter queue.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SagaCommandListener {

	private final SagaCommandHandler handler;
	private final ObjectMapper objectMapper;
	private final ObservabilityReporter observabilityReporter;

	@RabbitListener(queues = RabbitTopologyConfig.EXECUTION_COMMANDS_QUEUE)
	public void onCommand(Message amqpMessage) {
		SagaMessage message = objectMapper.readValue(
			new String(amqpMessage.getBody(), StandardCharsets.UTF_8),
			SagaMessage.class
		);

		if (message.correlationId() != null) {
			MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, message.correlationId());
			NewRelic.addCustomParameter(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, message.correlationId());
		}
		NewRelic.addCustomParameter("sagaId", String.valueOf(message.sagaId()));
		NewRelic.addCustomParameter("serviceOrderId", String.valueOf(message.serviceOrderId()));

		try {
			handler.handle(message);
		} catch (RuntimeException e) {
			log.error("Failed to handle command {} ({}): {}", message.messageId(), message.type(), e.getMessage());
			observabilityReporter.recordSagaFailure(message.type(), message.sagaId(), message.serviceOrderId(), e);
			throw e;
		} finally {
			MDC.remove(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
		}
	}
}
