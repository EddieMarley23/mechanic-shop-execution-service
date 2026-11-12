package org.project.mechanic_shop.execution.infrastructure.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Broker topology of the Saga.
 *
 * <p>OS owns the broker and shared exchanges. Execution owns its command queue and bindings.
 *
 * <pre>
 * saga.commands (direct) ──billing────▶ billing.commands    ──▶ Billing Service
 *                        ──execution──▶ execution.commands  ──▶ Execution Service
 * saga.events   (topic)  ──#──────────▶ os.saga-replies     ──▶ OS Service (orchestrator)
 * saga.dlx      (direct) ──▶ one ".dlq" queue per queue above
 * </pre>
 */
@Configuration
public class RabbitTopologyConfig {

	/** Direct exchange: the orchestrator routes each command to the queue of one participant. */
	public static final String COMMANDS_EXCHANGE = "saga.commands";
	/** Topic exchange: participants publish their replies, routed by message type. */
	public static final String EVENTS_EXCHANGE = "saga.events";
	public static final String DEAD_LETTER_EXCHANGE = "saga.dlx";

	public static final String BILLING_COMMANDS_QUEUE = "billing.commands";
	public static final String BILLING_ROUTING_KEY = "billing";
	public static final String EXECUTION_COMMANDS_QUEUE = "execution.commands";
	public static final String EXECUTION_ROUTING_KEY = "execution";
	public static final String SAGA_REPLIES_QUEUE = "os.saga-replies";

	private static final String DLQ_SUFFIX = ".dlq";

	@Bean
	public Declarables sagaTopology() {
		Queue executionCommands = queueWithDeadLetter(EXECUTION_COMMANDS_QUEUE);
		Queue executionDlq = deadLetterQueue(EXECUTION_COMMANDS_QUEUE);

		return new Declarables(
			executionCommands,
			executionDlq,
			new Binding(EXECUTION_COMMANDS_QUEUE, Binding.DestinationType.QUEUE,
				COMMANDS_EXCHANGE, EXECUTION_ROUTING_KEY, null),
			new Binding(executionDlq.getName(), Binding.DestinationType.QUEUE,
				DEAD_LETTER_EXCHANGE, executionDlq.getName(), null)
		);
	}

	private Queue queueWithDeadLetter(String name) {
		return QueueBuilder
			.durable(name)
			.deadLetterExchange(DEAD_LETTER_EXCHANGE)
			.deadLetterRoutingKey(name + DLQ_SUFFIX)
			.build();
	}

	private Queue deadLetterQueue(String sourceQueue) {
		return QueueBuilder.durable(sourceQueue + DLQ_SUFFIX).build();
	}
}
