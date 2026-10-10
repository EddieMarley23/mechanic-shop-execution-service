package org.project.mechanic_shop.execution.support;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.project.mechanic_shop.execution.application.ports.ProcessedMessagePort;
import org.project.mechanic_shop.execution.application.ports.SagaEventPublisher;
import org.project.mechanic_shop.execution.application.ports.WorkOrderRepositoryPort;
import org.project.mechanic_shop.execution.application.saga.SagaCommandHandler;
import org.project.mechanic_shop.execution.application.saga.SagaMessage;
import org.project.mechanic_shop.execution.application.saga.SagaMessageTypes;
import org.project.mechanic_shop.execution.application.saga.StartDiagnosisPayload;
import org.project.mechanic_shop.execution.application.services.Requester;
import org.project.mechanic_shop.execution.application.services.impl.WorkOrderServiceImpl;
import org.project.mechanic_shop.execution.domain.entities.work_order.WorkItem;
import org.project.mechanic_shop.execution.domain.entities.work_order.WorkOrder;
import org.project.mechanic_shop.execution.domain.enums.WorkItemTypeEnum;
import org.project.mechanic_shop.execution.domain.enums.WorkOrderStatusEnum;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Execution use cases wired to in-memory ports. The real service and the real command handler
 * run; only the database and the broker are replaced. Used by the unit tests and by the BDD steps,
 * which play the role of the Saga orchestrator by sending its commands.
 */
public class ExecutionTestHarness {

	public static final Requester MECHANIC = new Requester("mecanico@teste.com", "MECHANIC");

	/** A reply this service wrote to the outbox. */
	public record PublishedEvent(UUID sagaId, UUID serviceOrderId, String type, Map<String, Object> payload) {}

	public final MutableClock clock = new MutableClock(Instant.parse("2026-01-10T12:00:00Z"));
	public final ObjectMapper objectMapper = JsonMapper.builder().build();
	public final List<PublishedEvent> sagaEvents = new ArrayList<>();
	public final InMemoryWorkOrderRepository workOrderRepository = new InMemoryWorkOrderRepository();
	public final WorkOrderServiceImpl workOrderService;
	public final SagaCommandHandler commandHandler;

	public final UUID sagaId = UUID.randomUUID();
	public final UUID serviceOrderId = UUID.randomUUID();
	public final UUID laborId = UUID.randomUUID();
	public final UUID partId = UUID.randomUUID();

	private final Set<UUID> processedMessages = new HashSet<>();

	@SuppressWarnings("unchecked")
	public ExecutionTestHarness() {
		SagaEventPublisher sagaEventPublisher = (saga, order, type, payload) ->
			sagaEvents.add(new PublishedEvent(saga, order, type, objectMapper.convertValue(payload, Map.class)));
		ProcessedMessagePort processedMessagePort = (messageId, type) -> processedMessages.add(messageId);

		workOrderService = new WorkOrderServiceImpl(workOrderRepository, sagaEventPublisher, clock);
		commandHandler = new SagaCommandHandler(processedMessagePort, workOrderService, objectMapper);
	}

	// ------------------------------------------------------------------------------------ fixtures

	public StartDiagnosisPayload startDiagnosisPayload(String licensePlate) {
		return new StartDiagnosisPayload(
			licensePlate,
			"Volkswagen",
			"Gol",
			2021,
			"Cliente Teste",
			"Ruido ao frear",
			45210,
			"mecanico@teste.com"
		);
	}

	/** One labor and two units of a part, as a mechanic would list them. */
	public List<WorkItem> diagnosisItems() {
		return List.of(new WorkItem(WorkItemTypeEnum.LABOR, laborId, 1), new WorkItem(WorkItemTypeEnum.PART, partId, 2));
	}

	public SagaMessage command(UUID orderId, String type, Object payload) {
		return new SagaMessage(
			UUID.randomUUID(),
			sagaId,
			orderId,
			type,
			clock.instant(),
			"correlation-test",
			objectMapper.valueToTree(payload)
		);
	}

	public void receive(String type, Object payload) {
		commandHandler.handle(command(serviceOrderId, type, payload));
	}

	public void queueDiagnosis() {
		receive(SagaMessageTypes.DIAGNOSIS_START, startDiagnosisPayload("ABC1D23"));
	}

	/** Queues another order for diagnosis, later in the queue than the previous ones. */
	public UUID queueAnotherOrder(String licensePlate) {
		UUID otherOrderId = UUID.randomUUID();
		clock.advance(Duration.ofMinutes(1));
		commandHandler.handle(command(otherOrderId, SagaMessageTypes.DIAGNOSIS_START, startDiagnosisPayload(licensePlate)));
		return otherOrderId;
	}

	// ----------------------------------------------------------------------------------- inquiries

	public WorkOrder workOrder() {
		return workOrderRepository.findByServiceOrderId(serviceOrderId).orElseThrow();
	}

	public List<String> sagaEventTypes() {
		return sagaEvents.stream().map(PublishedEvent::type).toList();
	}

	public PublishedEvent lastEvent(String type) {
		return sagaEvents
			.reversed()
			.stream()
			.filter(e -> e.type().equals(type))
			.findFirst()
			.orElseThrow(() -> new AssertionError("Event not published: " + type + ". Published: " + sagaEventTypes()));
	}

	// ------------------------------------------------------------------------------ in-memory ports

	public static class MutableClock extends java.time.Clock {

		private Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		public void advance(Duration duration) {
			this.now = this.now.plus(duration);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public java.time.Clock withZone(ZoneId zone) {
			return this;
		}
	}

	public static class InMemoryWorkOrderRepository implements WorkOrderRepositoryPort {

		private final Map<UUID, WorkOrder> workOrders = new HashMap<>();

		@Override
		public WorkOrder save(WorkOrder workOrder) {
			workOrders.put(workOrder.getId(), workOrder);
			return workOrder;
		}

		@Override
		public Optional<WorkOrder> findByServiceOrderId(UUID serviceOrderId) {
			return workOrders.values().stream().filter(w -> w.getServiceOrderId().equals(serviceOrderId)).findFirst();
		}

		@Override
		public Page<WorkOrder> findQueue(List<WorkOrderStatusEnum> statuses, Pageable pageable) {
			List<WorkOrder> queue = workOrders
				.values()
				.stream()
				.filter(w -> statuses.contains(w.getStatus()))
				.sorted(Comparator.comparing(WorkOrder::getQueuedAt))
				.toList();
			return new PageImpl<>(queue, pageable, queue.size());
		}

		public int count() {
			return workOrders.size();
		}
	}
}
