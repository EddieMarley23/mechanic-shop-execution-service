package org.project.mechanic_shop.execution.application.services.impl;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.project.mechanic_shop.execution.application.ports.SagaEventPublisher;
import org.project.mechanic_shop.execution.application.ports.WorkOrderRepositoryPort;
import org.project.mechanic_shop.execution.application.saga.DiagnosisCompletedPayload;
import org.project.mechanic_shop.execution.application.saga.SagaMessageTypes;
import org.project.mechanic_shop.execution.application.saga.StartDiagnosisPayload;
import org.project.mechanic_shop.execution.application.services.Requester;
import org.project.mechanic_shop.execution.application.services.WorkOrderService;
import org.project.mechanic_shop.execution.domain.entities.work_order.VehicleSnapshot;
import org.project.mechanic_shop.execution.domain.entities.work_order.WorkItem;
import org.project.mechanic_shop.execution.domain.entities.work_order.WorkOrder;
import org.project.mechanic_shop.execution.domain.enums.WorkItemTypeEnum;
import org.project.mechanic_shop.execution.domain.enums.WorkOrderStatusEnum;
import org.project.mechanic_shop.execution.shared.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkOrderServiceImpl implements WorkOrderService {

	private final WorkOrderRepositoryPort workOrderRepository;
	private final SagaEventPublisher sagaEventPublisher;
	private final Clock clock;

	private static final String WORK_ORDER_NOT_FOUND_MSG = "Work order not found for Service Order: ";

	@Override
	@Transactional
	public WorkOrder queueForDiagnosis(UUID sagaId, UUID serviceOrderId, StartDiagnosisPayload payload) {
		Optional<WorkOrder> existing = workOrderRepository.findByServiceOrderId(serviceOrderId);
		if (existing.isPresent()) {
			log.info("Work order already queued for Service Order {}. Ignoring duplicated command.", serviceOrderId);
			return existing.get();
		}

		WorkOrder workOrder = WorkOrder.queueForDiagnosis(serviceOrderId, sagaId, Instant.now(clock));
		workOrder.setVehicle(
			new VehicleSnapshot(payload.licensePlate(), payload.brand(), payload.model(), payload.year())
		);
		workOrder.setCustomerName(payload.customerName());
		workOrder.setCustomerComplaint(payload.customerComplaint());
		workOrder.setOdometerReading(payload.odometerReading());
		workOrder.setAssignedMechanicEmail(payload.mechanicEmail());

		log.info("Service Order {} queued for diagnosis.", serviceOrderId);
		return workOrderRepository.save(workOrder);
	}

	@Override
	@Transactional
	public void reopenDiagnosis(UUID serviceOrderId, String reason) {
		WorkOrder workOrder = getByServiceOrderId(serviceOrderId);
		workOrder.reopenDiagnosis(reason, Instant.now(clock));
		workOrderRepository.save(workOrder);
		log.info("Diagnosis of Service Order {} reopened: {}", serviceOrderId, reason);
	}

	@Override
	@Transactional
	public void queueRepair(UUID serviceOrderId, Integer estimatedCompletionDays) {
		WorkOrder workOrder = getByServiceOrderId(serviceOrderId);
		workOrder.queueRepair(estimatedCompletionDays, Instant.now(clock));
		workOrderRepository.save(workOrder);
		log.info("Service Order {} queued for repair.", serviceOrderId);
	}

	@Override
	@Transactional
	public void cancel(UUID serviceOrderId, String reason) {
		workOrderRepository
			.findByServiceOrderId(serviceOrderId)
			.filter(workOrder -> workOrder.cancel(reason, Instant.now(clock)))
			.ifPresent(workOrder -> {
				workOrderRepository.save(workOrder);
				log.info("Work order of Service Order {} canceled by Saga compensation. Reason: {}", serviceOrderId, reason);
			});
	}

	@Override
	@Transactional
	public WorkOrder startDiagnosis(UUID serviceOrderId, Requester requester) {
		WorkOrder workOrder = getByServiceOrderId(serviceOrderId);
		workOrder.startDiagnosis(requester.email(), Instant.now(clock));
		WorkOrder saved = workOrderRepository.save(workOrder);

		publish(saved, SagaMessageTypes.DIAGNOSIS_STARTED, Map.of("mechanic", requester.email()));
		return saved;
	}

	@Override
	@Transactional
	public WorkOrder completeDiagnosis(UUID serviceOrderId, String diagnosis, List<WorkItem> items, Requester requester) {
		WorkOrder workOrder = getByServiceOrderId(serviceOrderId);
		workOrder.completeDiagnosis(diagnosis, items, requester.email(), Instant.now(clock));
		WorkOrder saved = workOrderRepository.save(workOrder);

		publish(
			saved,
			SagaMessageTypes.DIAGNOSIS_COMPLETED,
			new DiagnosisCompletedPayload(
				diagnosis,
				items
					.stream()
					.filter(i -> i.type() == WorkItemTypeEnum.LABOR)
					.map(i -> new DiagnosisCompletedPayload.Labor(i.referenceId(), i.quantity()))
					.toList(),
				items
					.stream()
					.filter(i -> i.type() == WorkItemTypeEnum.PART)
					.map(i -> new DiagnosisCompletedPayload.Part(i.referenceId(), i.quantity()))
					.toList()
			)
		);

		log.info("Diagnosis of Service Order {} completed with {} item(s).", serviceOrderId, items.size());
		return saved;
	}

	@Override
	@Transactional
	public WorkOrder startRepair(UUID serviceOrderId, Requester requester) {
		WorkOrder workOrder = getByServiceOrderId(serviceOrderId);
		workOrder.startRepair(requester.email(), Instant.now(clock));
		WorkOrder saved = workOrderRepository.save(workOrder);

		publish(saved, SagaMessageTypes.REPAIR_STARTED, Map.of("mechanic", requester.email()));
		return saved;
	}

	@Override
	@Transactional
	public WorkOrder completeRepair(UUID serviceOrderId, Requester requester) {
		WorkOrder workOrder = getByServiceOrderId(serviceOrderId);
		Instant now = Instant.now(clock);
		workOrder.completeRepair(requester.email(), now);
		WorkOrder saved = workOrderRepository.save(workOrder);

		publish(saved, SagaMessageTypes.REPAIR_COMPLETED, Map.of("completedAt", now.toString()));

		log.info("Repair of Service Order {} completed.", serviceOrderId);
		return saved;
	}

	@Override
	@Transactional
	public WorkOrder failRepair(UUID serviceOrderId, String reason, Requester requester) {
		WorkOrder workOrder = getByServiceOrderId(serviceOrderId);
		workOrder.failRepair(reason, requester.email(), Instant.now(clock));
		WorkOrder saved = workOrderRepository.save(workOrder);

		publish(saved, SagaMessageTypes.REPAIR_FAILED, Map.of("reason", reason));

		log.warn("Repair of Service Order {} failed: {}", serviceOrderId, reason);
		return saved;
	}

	@Override
	@Transactional(readOnly = true)
	public WorkOrder findByServiceOrderId(UUID serviceOrderId) {
		return getByServiceOrderId(serviceOrderId);
	}

	@Override
	@Transactional(readOnly = true)
	public Page<WorkOrder> findQueue(WorkOrderStatusEnum status, Pageable pageable) {
		List<WorkOrderStatusEnum> statuses = status != null
			? List.of(status)
			: Arrays.stream(WorkOrderStatusEnum.values()).filter(WorkOrderStatusEnum::isActive).toList();

		return workOrderRepository.findQueue(statuses, pageable);
	}

	private WorkOrder getByServiceOrderId(UUID serviceOrderId) {
		return workOrderRepository
			.findByServiceOrderId(serviceOrderId)
			.orElseThrow(() -> new ResourceNotFoundException(WORK_ORDER_NOT_FOUND_MSG + serviceOrderId));
	}

	private void publish(WorkOrder workOrder, String type, Object payload) {
		sagaEventPublisher.publish(workOrder.getSagaId(), workOrder.getServiceOrderId(), type, payload);
	}
}
