package org.project.mechanic_shop.execution.infrastructure.mongo.repositories;

import org.project.mechanic_shop.execution.infrastructure.mongo.documents.ProcessedMessageDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ProcessedMessageMongoRepository extends MongoRepository<ProcessedMessageDocument, String> {}
