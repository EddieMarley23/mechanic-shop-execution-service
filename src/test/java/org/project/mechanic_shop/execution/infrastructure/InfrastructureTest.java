package org.project.mechanic_shop.execution.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.project.mechanic_shop.execution.application.saga.SagaCommandHandler;
import org.project.mechanic_shop.execution.application.saga.SagaMessage;
import org.project.mechanic_shop.execution.application.saga.SagaMessageTypes;
import org.project.mechanic_shop.execution.domain.entities.work_order.VehicleSnapshot;
import org.project.mechanic_shop.execution.domain.entities.work_order.WorkItem;
import org.project.mechanic_shop.execution.domain.entities.work_order.WorkOrder;
import org.project.mechanic_shop.execution.domain.enums.WorkItemTypeEnum;
import org.project.mechanic_shop.execution.domain.enums.WorkOrderStatusEnum;
import org.project.mechanic_shop.execution.infrastructure.adapters.OutboxSagaEventPublisher;
import org.project.mechanic_shop.execution.infrastructure.adapters.ProcessedMessageAdapter;
import org.project.mechanic_shop.execution.infrastructure.adapters.WorkOrderRepositoryAdapter;
import org.project.mechanic_shop.execution.infrastructure.messaging.OutboxRelay;
import org.project.mechanic_shop.execution.infrastructure.messaging.RabbitTopologyConfig;
import org.project.mechanic_shop.execution.infrastructure.messaging.SagaCommandListener;
import org.project.mechanic_shop.execution.infrastructure.mongo.documents.OutboxMessageDocument;
import org.project.mechanic_shop.execution.infrastructure.mongo.documents.ProcessedMessageDocument;
import org.project.mechanic_shop.execution.infrastructure.mongo.documents.WorkOrderDocument;
import org.project.mechanic_shop.execution.infrastructure.mongo.mappers.WorkOrderDocumentMapper;
import org.project.mechanic_shop.execution.infrastructure.mongo.repositories.ProcessedMessageMongoRepository;
import org.project.mechanic_shop.execution.infrastructure.mongo.repositories.WorkOrderMongoRepository;
import org.project.mechanic_shop.execution.shared.config.observability.ObservabilityReporter;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class InfrastructureTest {

	private static final Instant NOW = Instant.parse("2026-01-10T12:00:00Z");
	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
	private final ObjectMapper objectMapper = JsonMapper.builder().build();

	private WorkOrder fullWorkOrder() {
		WorkOrder workOrder = WorkOrder.queueForDiagnosis(UUID.randomUUID(), UUID.randomUUID(), NOW);
		workOrder.setVehicle(new VehicleSnapshot("ABC1D23", "Volkswagen", "Gol", 2021));
		workOrder.setCustomerName("Cliente Teste");
		workOrder.setCustomerComplaint("Ruido ao frear");
		workOrder.setOdometerReading(45210);
		workOrder.setAssignedMechanicEmail("mecanico@teste.com");
		workOrder.startDiagnosis("mecanico@teste.com", NOW.plusSeconds(60));
		workOrder.completeDiagnosis(
			"Pastilhas desgastadas",
			List.of(new WorkItem(WorkItemTypeEnum.LABOR, UUID.randomUUID(), 1), new WorkItem(WorkItemTypeEnum.PART, UUID.randomUUID(), 2)),
			"mecanico@teste.com",
			NOW.plusSeconds(120)
		);
		workOrder.queueRepair(2, NOW.plusSeconds(180));
		return workOrder;
	}

	@Nested
	class DocumentMapping {

		private final WorkOrderDocumentMapper mapper = new WorkOrderDocumentMapper();

		@Test
		void shouldSurviveARoundTripThroughTheDocument() {
			WorkOrder original = fullWorkOrder();

			WorkOrderDocument document = mapper.toDocument(original);
			WorkOrder restored = mapper.toDomain(document);

			assertThat(document.getId()).isEqualTo(original.getId().toString());
			assertThat(document.getStatus()).isEqualTo("REPAIR_QUEUED");
			assertThat(restored).usingRecursiveComparison().isEqualTo(original);
		}

		@Test
		void shouldHandleAWorkOrderWithoutVehicleAndNullInputs() {
			WorkOrder withoutVehicle = WorkOrder.queueForDiagnosis(UUID.randomUUID(), UUID.randomUUID(), NOW);

			WorkOrder restored = mapper.toDomain(mapper.toDocument(withoutVehicle));

			assertThat(restored.getVehicle()).isNull();
			assertThat(restored.getItems()).isEmpty();
			assertThat(mapper.toDomain(null)).isNull();
			assertThat(mapper.toDocument(null)).isNull();
		}
	}

	@Nested
	class WorkOrderRepository {

		private final WorkOrderMongoRepository mongoRepository = mock(WorkOrderMongoRepository.class);
		private final WorkOrderDocumentMapper mapper = new WorkOrderDocumentMapper();
		private final WorkOrderRepositoryAdapter adapter = new WorkOrderRepositoryAdapter(mongoRepository, mapper);

		@Test
		void shouldSaveAndFindByServiceOrder() {
			WorkOrder workOrder = fullWorkOrder();
			when(mongoRepository.save(any(WorkOrderDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
			when(mongoRepository.findByServiceOrderId(workOrder.getServiceOrderId().toString())).thenReturn(
				Optional.of(mapper.toDocument(workOrder))
			);

			WorkOrder saved = adapter.save(workOrder);
			Optional<WorkOrder> found = adapter.findByServiceOrderId(workOrder.getServiceOrderId());

			assertThat(saved.getId()).isEqualTo(workOrder.getId());
			assertThat(found).get().extracting(WorkOrder::getStatus).isEqualTo(WorkOrderStatusEnum.REPAIR_QUEUED);
		}

		@Test
		void shouldQueryTheQueueIgnoringClientSorting() {
			when(mongoRepository.findByStatusInOrderByQueuedAtAsc(any(), any(Pageable.class))).thenReturn(
				new PageImpl<>(List.of(mapper.toDocument(fullWorkOrder())))
			);

			var page = adapter.findQueue(
				List.of(WorkOrderStatusEnum.DIAGNOSIS_QUEUED, WorkOrderStatusEnum.REPAIR_QUEUED),
				PageRequest.of(1, 5, Sort.by("customerName"))
			);

			ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
			verify(mongoRepository).findByStatusInOrderByQueuedAtAsc(
				eq(List.of("DIAGNOSIS_QUEUED", "REPAIR_QUEUED")),
				pageable.capture()
			);
			assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
			assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
			assertThat(pageable.getValue().getSort().isUnsorted()).isTrue();
			assertThat(page.getContent()).hasSize(1);
		}
	}

	@Nested
	class EventPublisher {

		private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
		private final OutboxSagaEventPublisher publisher = new OutboxSagaEventPublisher(mongoTemplate, objectMapper, clock);

		@Test
		void shouldStoreTheEventInTheOutboxRoutedByItsType() {
			UUID sagaId = UUID.randomUUID();
			UUID serviceOrderId = UUID.randomUUID();

			publisher.publish(sagaId, serviceOrderId, SagaMessageTypes.REPAIR_COMPLETED, Map.of("completedAt", "now"));

			ArgumentCaptor<OutboxMessageDocument> captor = ArgumentCaptor.forClass(OutboxMessageDocument.class);
			verify(mongoTemplate).insert(captor.capture());
			OutboxMessageDocument outbox = captor.getValue();
			assertThat(outbox.getExchange()).isEqualTo(RabbitTopologyConfig.EVENTS_EXCHANGE);
			assertThat(outbox.getRoutingKey()).isEqualTo(SagaMessageTypes.REPAIR_COMPLETED);
			assertThat(outbox.getCreatedAt()).isEqualTo(NOW);
			assertThat(outbox.getPublishedAt()).isNull();

			SagaMessage envelope = objectMapper.readValue(outbox.getPayload(), SagaMessage.class);
			assertThat(envelope.messageId()).hasToString(outbox.getId());
			assertThat(envelope.sagaId()).isEqualTo(sagaId);
			assertThat(envelope.serviceOrderId()).isEqualTo(serviceOrderId);
			assertThat(envelope.payload().get("completedAt").asString()).isEqualTo("now");
		}
	}

	@Nested
	class Relay {

		private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
		private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
		private final OutboxRelay relay = new OutboxRelay(mongoTemplate, rabbitTemplate, clock);

		private OutboxMessageDocument pending() {
			OutboxMessageDocument outbox = new OutboxMessageDocument();
			outbox.setId(UUID.randomUUID().toString());
			outbox.setExchange(RabbitTopologyConfig.EVENTS_EXCHANGE);
			outbox.setRoutingKey(SagaMessageTypes.REPAIR_COMPLETED);
			outbox.setMessageType(SagaMessageTypes.REPAIR_COMPLETED);
			outbox.setPayload("{\"type\":\"execution.repair.completed\"}");
			outbox.setCreatedAt(NOW);
			return outbox;
		}

		private void claimsInOrder(OutboxMessageDocument first, OutboxMessageDocument... next) {
			when(
				mongoTemplate.findAndModify(
					any(Query.class),
					any(UpdateDefinition.class),
					any(FindAndModifyOptions.class),
					eq(OutboxMessageDocument.class)
				)
			).thenReturn(first, next);
		}

		@Test
		void shouldClaimSendAndMarkEachPendingMessage() {
			ReflectionTestUtils.setField(relay, "batchSize", 50);
			OutboxMessageDocument first = pending();
			OutboxMessageDocument second = pending();
			claimsInOrder(first, second, null);

			relay.relayPending();

			ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
			verify(rabbitTemplate, times(2)).send(
				eq(RabbitTopologyConfig.EVENTS_EXCHANGE),
				eq(SagaMessageTypes.REPAIR_COMPLETED),
				captor.capture()
			);
			assertThat(new String(captor.getAllValues().get(0).getBody(), StandardCharsets.UTF_8)).isEqualTo(first.getPayload());
			assertThat(captor.getAllValues().get(0).getMessageProperties().getMessageId()).isEqualTo(first.getId());
			// One update per message: publishedAt set, lease removed.
			verify(mongoTemplate, times(2)).updateFirst(any(Query.class), any(UpdateDefinition.class), eq(OutboxMessageDocument.class));
		}

		@Test
		void shouldStopAtTheBatchSize() {
			ReflectionTestUtils.setField(relay, "batchSize", 1);
			claimsInOrder(pending(), pending(), pending());

			relay.relayPending();

			verify(rabbitTemplate, times(1)).send(any(String.class), any(String.class), any(Message.class));
		}

		@Test
		void shouldReleaseTheLeaseAndStopWhenTheBrokerIsDown() {
			ReflectionTestUtils.setField(relay, "batchSize", 50);
			claimsInOrder(pending(), pending());
			doThrow(new AmqpConnectException(new RuntimeException("down")))
				.when(rabbitTemplate)
				.send(any(String.class), any(String.class), any(Message.class));

			relay.relayPending();

			verify(rabbitTemplate, times(1)).send(any(String.class), any(String.class), any(Message.class));
			ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
			verify(mongoTemplate, times(1)).updateFirst(any(Query.class), update.capture(), eq(OutboxMessageDocument.class));
			assertThat(update.getValue().getUpdateObject().toJson()).contains("$unset").doesNotContain("publishedAt");
		}

		@Test
		void shouldDoNothingWhenTheOutboxIsEmpty() {
			ReflectionTestUtils.setField(relay, "batchSize", 50);

			relay.relayPending();

			verify(rabbitTemplate, never()).send(any(String.class), any(String.class), any(Message.class));
		}
	}

	@Nested
	class CommandListener {

		private final SagaCommandHandler handler = mock(SagaCommandHandler.class);
		private final ObservabilityReporter reporter = mock(ObservabilityReporter.class);
		private final SagaCommandListener listener = new SagaCommandListener(handler, objectMapper, reporter);

		private SagaMessage command(String correlationId) {
			return new SagaMessage(
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				SagaMessageTypes.REPAIR_START,
				NOW,
				correlationId,
				objectMapper.valueToTree(Map.of("estimatedCompletionDays", 2))
			);
		}

		@Test
		void shouldDeserializeTheEnvelopeAndDelegateToTheHandler() {
			SagaMessage command = command("corr-1");

			listener.onCommand(MessageBuilder.withBody(objectMapper.writeValueAsBytes(command)).build());

			ArgumentCaptor<SagaMessage> captor = ArgumentCaptor.forClass(SagaMessage.class);
			verify(handler).handle(captor.capture());
			assertThat(captor.getValue().messageId()).isEqualTo(command.messageId());
			assertThat(captor.getValue().payload().get("estimatedCompletionDays").asInt()).isEqualTo(2);
		}

		@Test
		void shouldReportAndRethrowSoTheBrokerRetriesAndDeadLetters() {
			SagaMessage command = command(null);
			IllegalStateException failure = new IllegalStateException("boom");
			doThrow(failure).when(handler).handle(any());

			assertThatThrownBy(() -> listener.onCommand(MessageBuilder.withBody(objectMapper.writeValueAsBytes(command)).build()))
				.isSameAs(failure);

			verify(reporter).recordSagaFailure(command.type(), command.sagaId(), command.serviceOrderId(), failure);
		}
	}

	@Nested
	class ProcessedMessages {

		private final ProcessedMessageMongoRepository repository = mock(ProcessedMessageMongoRepository.class);
		private final ProcessedMessageAdapter adapter = new ProcessedMessageAdapter(repository, clock);

		@Test
		void shouldRegisterNewMessagesAndRefuseRedeliveries() {
			UUID fresh = UUID.randomUUID();
			UUID redelivered = UUID.randomUUID();
			when(repository.existsById(fresh.toString())).thenReturn(false);
			when(repository.existsById(redelivered.toString())).thenReturn(true);

			assertThat(adapter.registerIfNew(fresh, "execution.repair.start")).isTrue();
			assertThat(adapter.registerIfNew(redelivered, "execution.repair.start")).isFalse();

			ArgumentCaptor<ProcessedMessageDocument> captor = ArgumentCaptor.forClass(ProcessedMessageDocument.class);
			verify(repository, times(1)).insert(captor.capture());
			assertThat(captor.getValue().getMessageId()).isEqualTo(fresh.toString());
			assertThat(captor.getValue().getProcessedAt()).isEqualTo(NOW);
		}
	}

	@Nested
	class Topology {

		@Test
		void shouldDeclareEveryQueueWithItsDeadLetterQueue() {
			List<Declarable> declarables = List.copyOf(new RabbitTopologyConfig().sagaTopology().getDeclarables());

			List<String> queues = declarables.stream().filter(Queue.class::isInstance).map(d -> ((Queue) d).getName()).toList();
			assertThat(queues).containsExactlyInAnyOrder(
				"billing.commands",
				"billing.commands.dlq",
				"execution.commands",
				"execution.commands.dlq",
				"os.saga-replies",
				"os.saga-replies.dlq"
			);
		}
	}

	@Nested
	class Observability {

		@Test
		void shouldNeverFailTheCallerWhenReportingTelemetry() {
			ObservabilityReporter reporter = new ObservabilityReporter();

			reporter.recordSagaFailure("execution.repair.start", UUID.randomUUID(), UUID.randomUUID(), new RuntimeException("x"));
			reporter.recordIntegrationFailure("RabbitMQ", "send", new RuntimeException("x"));
		}
	}
}
