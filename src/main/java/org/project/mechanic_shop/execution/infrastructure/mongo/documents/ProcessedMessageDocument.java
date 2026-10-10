package org.project.mechanic_shop.execution.infrastructure.mongo.documents;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "processed_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedMessageDocument {

	@Id
	private String messageId;

	private String messageType;
	private Instant processedAt;
}
