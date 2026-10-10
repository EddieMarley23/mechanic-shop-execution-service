package org.project.mechanic_shop.execution.domain.dto;

import java.time.Instant;
import java.util.UUID;
import org.project.mechanic_shop.execution.domain.entities.work_order.WorkOrder;
import org.project.mechanic_shop.execution.domain.enums.WorkOrderStatusEnum;

public record WorkOrderShortDto(
	UUID serviceOrderId,
	String licensePlate,
	String customerComplaint,
	String assignedMechanicEmail,
	WorkOrderStatusEnum status,
	Instant queuedAt
) {
	public static WorkOrderShortDto from(WorkOrder workOrder) {
		return new WorkOrderShortDto(
			workOrder.getServiceOrderId(),
			workOrder.getVehicle() != null ? workOrder.getVehicle().licensePlate() : null,
			workOrder.getCustomerComplaint(),
			workOrder.getAssignedMechanicEmail(),
			workOrder.getStatus(),
			workOrder.getQueuedAt()
		);
	}
}
