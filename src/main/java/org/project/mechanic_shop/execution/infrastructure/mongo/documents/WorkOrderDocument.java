package org.project.mechanic_shop.execution.infrastructure.mongo.documents;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One document per service order, with its whole progress history embedded. Identifiers are stored
 * as strings so the documents stay readable regardless of the driver's UUID representation.
 */
@Document(collection = "work_orders")
@CompoundIndex(name = "idx_queue", def = "{'status': 1, 'queuedAt': 1}")
@Getter
@Setter
@NoArgsConstructor
public class WorkOrderDocument {

	@Id
	private String id;

	@Indexed(unique = true)
	private String serviceOrderId;

	private String sagaId;
	private Vehicle vehicle;
	private String customerName;
	private String customerComplaint;
	private Integer odometerReading;
	private String assignedMechanicEmail;
	private String status;
	private String diagnosis;
	private List<Item> items = new ArrayList<>();
	private Integer estimatedCompletionDays;
	private List<Progress> history = new ArrayList<>();
	private Instant queuedAt;
	private Instant createdAt;
	private Instant updatedAt;

	public record Vehicle(String licensePlate, String brand, String model, Integer year) {}

	public record Item(String type, String referenceId, Integer quantity) {}

	public record Progress(String status, Instant at, String by, String note) {}
}
