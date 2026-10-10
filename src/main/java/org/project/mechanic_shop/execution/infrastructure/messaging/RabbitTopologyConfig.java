package org.project.mechanic_shop.execution.infrastructure.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Broker topology of the Saga.
 *
 * <p>The three services declare the whole topology with the same arguments. Declarations are
 * idempotent, so it does not matter which service starts first — and no message is dropped because
 * its destination queue did not exist yet.
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
		DirectExchange commands = new DirectExchange(COMMANDS_EXCHANGE, true, false);
		TopicExchange events = new TopicExchange(EVENTS_EXCHANGE, true, false);
		DirectExchange deadLetters = new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);

		Queue billingCommands = queueWithDeadLetter(BILLING_COMMANDS_QUEUE);
		Queue executionCommands = queueWithDeadLetter(EXECUTION_COMMANDS_QUEUE);
		Queue sagaReplies = queueWithDeadLetter(SAGA_REPLIES_QUEUE);

		Queue billingDlq = deadLetterQueue(BILLING_COMMANDS_QUEUE);
		Queue executionDlq = deadLetterQueue(EXECUTION_COMMANDS_QUEUE);
		Queue sagaRepliesDlq = deadLetterQueue(SAGA_REPLIES_QUEUE);

		return new Declarables(
			commands,
			events,
			deadLetters,
			billingCommands,
			executionCommands,
			sagaReplies,
			billingDlq,
			executionDlq,
			sagaRepliesDlq,
			BindingBuilder.bind(billingCommands).to(commands).with(BILLING_ROUTING_KEY),
			BindingBuilder.bind(executionCommands).to(commands).with(EXECUTION_ROUTING_KEY),
			BindingBuilder.bind(sagaReplies).to(events).with("#"),
			deadLetterBinding(billingDlq, deadLetters),
			deadLetterBinding(executionDlq, deadLetters),
			deadLetterBinding(sagaRepliesDlq, deadLetters)
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

	private Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange deadLetters) {
		return BindingBuilder.bind(deadLetterQueue).to(deadLetters).with(deadLetterQueue.getName());
	}
}
