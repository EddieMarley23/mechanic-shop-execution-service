package org.project.mechanic_shop.execution.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.project.mechanic_shop.execution.infrastructure.mongo.documents.OutboxMessageDocument;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Relays the outbox to RabbitMQ.
 *
 * <p>MongoDB has no {@code SKIP LOCKED}, so each message is claimed with an atomic
 * {@code findAndModify} that sets a short lease ({@code lockedUntil}). Other replicas skip leased
 * messages; if the owner crashes, the lease expires and the message is relayed again. A resend is
 * possible, a loss is not — consumers are idempotent.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

	private static final Duration LEASE = Duration.ofSeconds(30);
	private static final String PUBLISHED_AT = "publishedAt";
	private static final String LOCKED_UNTIL = "lockedUntil";

	private final MongoTemplate mongoTemplate;
	private final RabbitTemplate rabbitTemplate;
	private final Clock clock;

	@Value("${app.outbox.batch-size:50}")
	private int batchSize;

	@Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
	public void relayPending() {
		for (int i = 0; i < batchSize; i++) {
			OutboxMessageDocument outbox = claimNext();
			if (outbox == null) return;

			Message message = MessageBuilder
				.withBody(outbox.getPayload().getBytes(StandardCharsets.UTF_8))
				.setContentType(MessageProperties.CONTENT_TYPE_JSON)
				.setContentEncoding(StandardCharsets.UTF_8.name())
				.setMessageId(outbox.getId())
				.setType(outbox.getMessageType())
				.setDeliveryMode(MessageDeliveryMode.PERSISTENT)
				.build();

			try {
				rabbitTemplate.send(outbox.getExchange(), outbox.getRoutingKey(), message);
			} catch (AmqpException e) {
				log.warn("Broker unavailable. Outbox message {} will be retried: {}", outbox.getId(), e.getMessage());
				release(outbox);
				return;
			}

			mongoTemplate.updateFirst(
				Query.query(Criteria.where("_id").is(outbox.getId())),
				new Update().set(PUBLISHED_AT, Instant.now(clock)).unset(LOCKED_UNTIL),
				OutboxMessageDocument.class
			);
		}
	}

	private OutboxMessageDocument claimNext() {
		Instant now = Instant.now(clock);

		Query pending = Query
			.query(
				new Criteria()
					.andOperator(
						Criteria.where(PUBLISHED_AT).isNull(),
						new Criteria().orOperator(Criteria.where(LOCKED_UNTIL).isNull(), Criteria.where(LOCKED_UNTIL).lt(now))
					)
			)
			.with(Sort.by(Sort.Direction.ASC, "createdAt"));

		return mongoTemplate.findAndModify(
			pending,
			new Update().set(LOCKED_UNTIL, now.plus(LEASE)),
			FindAndModifyOptions.options().returnNew(true),
			OutboxMessageDocument.class
		);
	}

	private void release(OutboxMessageDocument outbox) {
		mongoTemplate.updateFirst(
			Query.query(Criteria.where("_id").is(outbox.getId())),
			new Update().unset(LOCKED_UNTIL),
			OutboxMessageDocument.class
		);
	}
}
