package org.project.mechanic_shop.execution.application.ports;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.project.mechanic_shop.execution.domain.entities.work_order.WorkOrder;
import org.project.mechanic_shop.execution.domain.enums.WorkOrderStatusEnum;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface WorkOrderRepositoryPort {
	WorkOrder save(WorkOrder workOrder);
	Optional<WorkOrder> findByServiceOrderId(UUID serviceOrderId);
	/** Work orders in the given statuses, oldest in the queue first. */
	Page<WorkOrder> findQueue(List<WorkOrderStatusEnum> statuses, Pageable pageable);
}
