package org.project.mechanic_shop.execution.infrastructure.adapters;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.project.mechanic_shop.execution.application.ports.ProcessedMessagePort;
import org.project.mechanic_shop.execution.infrastructure.mongo.documents.ProcessedMessageDocument;
import org.project.mechanic_shop.execution.infrastructure.mongo.repositories.ProcessedMessageMongoRepository;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProcessedMessageAdapter implements ProcessedMessagePort {

	private final ProcessedMessageMongoRepository repository;
	private final Clock clock;

	/**
	 * Two concurrent deliveries of the same message can both pass the {@code existsById} check; the
	 * unique {@code _id} then makes the second transaction fail and roll back, and its redelivery is
	 * ignored.
	 */
	@Override
	public boolean registerIfNew(UUID messageId, String type) {
		if (repository.existsById(messageId.toString())) return false;

		repository.insert(new ProcessedMessageDocument(messageId.toString(), type, Instant.now(clock)));
		return true;
	}
}
