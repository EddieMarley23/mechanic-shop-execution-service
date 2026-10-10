package org.project.mechanic_shop.execution.application.services;

import java.util.List;
import java.util.UUID;
import org.project.mechanic_shop.execution.application.saga.StartDiagnosisPayload;
import org.project.mechanic_shop.execution.domain.entities.work_order.WorkItem;
import org.project.mechanic_shop.execution.domain.entities.work_order.WorkOrder;
import org.project.mechanic_shop.execution.domain.enums.WorkOrderStatusEnum;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface WorkOrderService {
	// Driven by Saga commands
	WorkOrder queueForDiagnosis(UUID sagaId, UUID serviceOrderId, StartDiagnosisPayload payload);
	void reopenDiagnosis(UUID serviceOrderId, String reason);
	void queueRepair(UUID serviceOrderId, Integer estimatedCompletionDays);
	void cancel(UUID serviceOrderId, String reason);

	// Driven by the mechanics
	WorkOrder startDiagnosis(UUID serviceOrderId, Requester requester);
	WorkOrder completeDiagnosis(UUID serviceOrderId, String diagnosis, List<WorkItem> items, Requester requester);
	WorkOrder startRepair(UUID serviceOrderId, Requester requester);
	WorkOrder completeRepair(UUID serviceOrderId, Requester requester);
	WorkOrder failRepair(UUID serviceOrderId, String reason, Requester requester);

	WorkOrder findByServiceOrderId(UUID serviceOrderId);
	Page<WorkOrder> findQueue(WorkOrderStatusEnum status, Pageable pageable);
}
