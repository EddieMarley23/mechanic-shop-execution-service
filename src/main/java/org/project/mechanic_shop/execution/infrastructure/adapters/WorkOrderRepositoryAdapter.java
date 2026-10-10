package org.project.mechanic_shop.execution.infrastructure.adapters;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.project.mechanic_shop.execution.application.ports.WorkOrderRepositoryPort;
import org.project.mechanic_shop.execution.domain.entities.work_order.WorkOrder;
import org.project.mechanic_shop.execution.domain.enums.WorkOrderStatusEnum;
import org.project.mechanic_shop.execution.infrastructure.mongo.mappers.WorkOrderDocumentMapper;
import org.project.mechanic_shop.execution.infrastructure.mongo.repositories.WorkOrderMongoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class WorkOrderRepositoryAdapter implements WorkOrderRepositoryPort {

	private final WorkOrderMongoRepository mongoRepository;
	private final WorkOrderDocumentMapper mapper;

	@Override
	public WorkOrder save(WorkOrder workOrder) {
		return mapper.toDomain(mongoRepository.save(mapper.toDocument(workOrder)));
	}

	@Override
	public Optional<WorkOrder> findByServiceOrderId(UUID serviceOrderId) {
		return mongoRepository.findByServiceOrderId(serviceOrderId.toString()).map(mapper::toDomain);
	}

	@Override
	public Page<WorkOrder> findQueue(List<WorkOrderStatusEnum> statuses, Pageable pageable) {
		// The queue order is a business rule (first in, first out), so client-supplied sorting is dropped.
		Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

		return mongoRepository
			.findByStatusInOrderByQueuedAtAsc(statuses.stream().map(Enum::name).toList(), unsorted)
			.map(mapper::toDomain);
	}
}
