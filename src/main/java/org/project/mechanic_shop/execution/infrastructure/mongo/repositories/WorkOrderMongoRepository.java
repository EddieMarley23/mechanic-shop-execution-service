package org.project.mechanic_shop.execution.infrastructure.mongo.repositories;

import java.util.List;
import java.util.Optional;
import org.project.mechanic_shop.execution.infrastructure.mongo.documents.WorkOrderDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface WorkOrderMongoRepository extends MongoRepository<WorkOrderDocument, String> {
	Optional<WorkOrderDocument> findByServiceOrderId(String serviceOrderId);
	Page<WorkOrderDocument> findByStatusInOrderByQueuedAtAsc(List<String> statuses, Pageable pageable);
}
