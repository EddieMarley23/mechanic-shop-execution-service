package org.project.mechanic_shop.execution.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.project.mechanic_shop.execution.domain.entities.work_order.ProgressEntry;
import org.project.mechanic_shop.execution.domain.entities.work_order.VehicleSnapshot;
import org.project.mechanic_shop.execution.domain.entities.work_order.WorkItem;
import org.project.mechanic_shop.execution.domain.entities.work_order.WorkOrder;
import org.project.mechanic_shop.execution.domain.enums.WorkOrderStatusEnum;

@Schema(description = "Execution of a service order, with its full progress history")
public record WorkOrderDto(
	UUID id,
	@Schema(description = "External ID of the service order in the OS Service") UUID serviceOrderId,
	VehicleSnapshot vehicle,
	String customerName,
	String customerComplaint,
	Integer odometerReading,
	String assignedMechanicEmail,
	WorkOrderStatusEnum status,
	String diagnosis,
	List<WorkItem> items,
	Integer estimatedCompletionDays,
	List<ProgressEntry> history,
	Instant queuedAt,
	Instant createdAt,
	Instant updatedAt
) {
	public static WorkOrderDto from(WorkOrder workOrder) {
		return new WorkOrderDto(
			workOrder.getId(),
			workOrder.getServiceOrderId(),
			workOrder.getVehicle(),
			workOrder.getCustomerName(),
			workOrder.getCustomerComplaint(),
			workOrder.getOdometerReading(),
			workOrder.getAssignedMechanicEmail(),
			workOrder.getStatus(),
			workOrder.getDiagnosis(),
			workOrder.getItems(),
			workOrder.getEstimatedCompletionDays(),
			workOrder.getHistory(),
			workOrder.getQueuedAt(),
			workOrder.getCreatedAt(),
			workOrder.getUpdatedAt()
		);
	}
}
