package org.project.mechanic_shop.execution.infrastructure.mongo.documents;

import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A message waiting to be relayed to the broker. Written in the same transaction as the state change. */
@Document(collection = "outbox_messages")
@CompoundIndex(name = "idx_outbox_pending", def = "{'publishedAt': 1, 'createdAt': 1}")
@Getter
@Setter
@NoArgsConstructor
public class OutboxMessageDocument {

	@Id
	private String id;

	private String exchange;
	private String routingKey;
	private String messageType;
	private String payload;
	private Instant createdAt;
	private Instant publishedAt;
	/** While in the future, a relay instance owns this message and the others skip it. */
	private Instant lockedUntil;
}
