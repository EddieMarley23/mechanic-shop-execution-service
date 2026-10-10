package org.project.mechanic_shop.execution.infrastructure.mongo.mappers;

import java.util.ArrayList;
import java.util.UUID;
import org.project.mechanic_shop.execution.domain.entities.work_order.ProgressEntry;
import org.project.mechanic_shop.execution.domain.entities.work_order.VehicleSnapshot;
import org.project.mechanic_shop.execution.domain.entities.work_order.WorkItem;
import org.project.mechanic_shop.execution.domain.entities.work_order.WorkOrder;
import org.project.mechanic_shop.execution.domain.enums.WorkItemTypeEnum;
import org.project.mechanic_shop.execution.domain.enums.WorkOrderStatusEnum;
import org.project.mechanic_shop.execution.infrastructure.mongo.documents.WorkOrderDocument;
import org.springframework.stereotype.Component;

@Component
public class WorkOrderDocumentMapper {

	public WorkOrder toDomain(WorkOrderDocument doc) {
		if (doc == null) return null;

		WorkOrder workOrder = new WorkOrder();
		workOrder.setId(UUID.fromString(doc.getId()));
		workOrder.setServiceOrderId(UUID.fromString(doc.getServiceOrderId()));
		workOrder.setSagaId(UUID.fromString(doc.getSagaId()));
		if (doc.getVehicle() != null) {
			workOrder.setVehicle(
				new VehicleSnapshot(
					doc.getVehicle().licensePlate(),
					doc.getVehicle().brand(),
					doc.getVehicle().model(),
					doc.getVehicle().year()
				)
			);
		}
		workOrder.setCustomerName(doc.getCustomerName());
		workOrder.setCustomerComplaint(doc.getCustomerComplaint());
		workOrder.setOdometerReading(doc.getOdometerReading());
		workOrder.setAssignedMechanicEmail(doc.getAssignedMechanicEmail());
		workOrder.setStatus(WorkOrderStatusEnum.valueOf(doc.getStatus()));
		workOrder.setDiagnosis(doc.getDiagnosis());
		workOrder.setItems(
			new ArrayList<>(
				doc
					.getItems()
					.stream()
					.map(i ->
						new WorkItem(WorkItemTypeEnum.valueOf(i.type()), UUID.fromString(i.referenceId()), i.quantity())
					)
					.toList()
			)
		);
		workOrder.setEstimatedCompletionDays(doc.getEstimatedCompletionDays());
		workOrder.setHistory(
			new ArrayList<>(
				doc
					.getHistory()
					.stream()
					.map(h -> new ProgressEntry(WorkOrderStatusEnum.valueOf(h.status()), h.at(), h.by(), h.note()))
					.toList()
			)
		);
		workOrder.setQueuedAt(doc.getQueuedAt());
		workOrder.setCreatedAt(doc.getCreatedAt());
		workOrder.setUpdatedAt(doc.getUpdatedAt());
		return workOrder;
	}

	public WorkOrderDocument toDocument(WorkOrder workOrder) {
		if (workOrder == null) return null;

		WorkOrderDocument doc = new WorkOrderDocument();
		doc.setId(workOrder.getId().toString());
		doc.setServiceOrderId(workOrder.getServiceOrderId().toString());
		doc.setSagaId(workOrder.getSagaId().toString());
		if (workOrder.getVehicle() != null) {
			doc.setVehicle(
				new WorkOrderDocument.Vehicle(
					workOrder.getVehicle().licensePlate(),
					workOrder.getVehicle().brand(),
					workOrder.getVehicle().model(),
					workOrder.getVehicle().year()
				)
			);
		}
		doc.setCustomerName(workOrder.getCustomerName());
		doc.setCustomerComplaint(workOrder.getCustomerComplaint());
		doc.setOdometerReading(workOrder.getOdometerReading());
		doc.setAssignedMechanicEmail(workOrder.getAssignedMechanicEmail());
		doc.setStatus(workOrder.getStatus().name());
		doc.setDiagnosis(workOrder.getDiagnosis());
		doc.setItems(
			new ArrayList<>(
				workOrder
					.getItems()
					.stream()
					.map(i -> new WorkOrderDocument.Item(i.type().name(), i.referenceId().toString(), i.quantity()))
					.toList()
			)
		);
		doc.setEstimatedCompletionDays(workOrder.getEstimatedCompletionDays());
		doc.setHistory(
			new ArrayList<>(
				workOrder
					.getHistory()
					.stream()
					.map(h -> new WorkOrderDocument.Progress(h.status().name(), h.at(), h.by(), h.note()))
					.toList()
			)
		);
		doc.setQueuedAt(workOrder.getQueuedAt());
		doc.setCreatedAt(workOrder.getCreatedAt());
		doc.setUpdatedAt(workOrder.getUpdatedAt());
		return doc;
	}
}
