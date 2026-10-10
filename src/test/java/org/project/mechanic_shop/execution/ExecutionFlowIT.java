package org.project.mechanic_shop.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.project.mechanic_shop.execution.application.saga.SagaMessage;
import org.project.mechanic_shop.execution.application.saga.SagaMessageTypes;
import org.project.mechanic_shop.execution.application.saga.StartDiagnosisPayload;
import org.project.mechanic_shop.execution.infrastructure.messaging.OutboxRelay;
import org.project.mechanic_shop.execution.infrastructure.messaging.RabbitTopologyConfig;
import org.project.mechanic_shop.execution.infrastructure.messaging.SagaCommandListener;
import org.project.mechanic_shop.execution.infrastructure.mongo.documents.OutboxMessageDocument;
import org.project.mechanic_shop.execution.infrastructure.mongo.documents.ProcessedMessageDocument;
import org.project.mechanic_shop.execution.infrastructure.mongo.documents.WorkOrderDocument;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * The whole application against a real MongoDB replica set: document mappings, indexes, the
 * transactional outbox and the security filter chain. Only the broker connection is replaced.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExecutionFlowIT {

	// MongoDBContainer starts a single-node replica set, which is what transactions require.
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

	static {
		MONGO.start();
	}

	@Autowired
	private SagaCommandListener commandListener;

	@Autowired
	private OutboxRelay outboxRelay;

	@Autowired
	private MongoTemplate mongoTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private RabbitTemplate rabbitTemplate;

	@Value("${api.security.token.secret}")
	private String jwtSecret;

	@Value("${api.security.token.issuer}")
	private String jwtIssuer;

	private UUID sagaId;
	private UUID serviceOrderId;

	@BeforeEach
	void setup() {
		mongoTemplate.remove(new Query(), OutboxMessageDocument.class);
		sagaId = UUID.randomUUID();
		serviceOrderId = UUID.randomUUID();
	}

	private String token(String role) {
		return JWT
			.create()
			.withIssuer(jwtIssuer)
			.withSubject("mecanico@teste.com")
			.withClaim("role", role)
			.withExpiresAt(Instant.now().plusSeconds(600))
			.sign(Algorithm.HMAC256(jwtSecret));
	}

	private SagaMessage command(String type, Object payload) {
		return new SagaMessage(
			UUID.randomUUID(),
			sagaId,
			serviceOrderId,
			type,
			Instant.now(),
			"corr-it",
			objectMapper.valueToTree(payload)
		);
	}

	private void deliver(SagaMessage command) {
		commandListener.onCommand(MessageBuilder.withBody(objectMapper.writeValueAsBytes(command)).build());
	}

	private void queueForDiagnosis() {
		deliver(
			command(
				SagaMessageTypes.DIAGNOSIS_START,
				new StartDiagnosisPayload("ABC1D23", "Volkswagen", "Gol", 2021, "Cliente", "Ruido ao frear", 45210, null)
			)
		);
	}

	private List<OutboxMessageDocument> outbox() {
		return mongoTemplate.findAll(OutboxMessageDocument.class);
	}

	@Test
	void shouldQueueTheWorkOrderOnceEvenWhenTheCommandIsRedelivered() {
		SagaMessage start = command(
			SagaMessageTypes.DIAGNOSIS_START,
			new StartDiagnosisPayload("ABC1D23", "Volkswagen", "Gol", 2021, "Cliente", "Ruido ao frear", 45210, null)
		);

		deliver(start);
		deliver(start);

		List<WorkOrderDocument> workOrders = mongoTemplate.find(
			Query.query(Criteria.where("serviceOrderId").is(serviceOrderId.toString())),
			WorkOrderDocument.class
		);
		assertThat(workOrders)
			.singleElement()
			.satisfies(doc -> {
				assertThat(doc.getStatus()).isEqualTo("DIAGNOSIS_QUEUED");
				assertThat(doc.getVehicle().licensePlate()).isEqualTo("ABC1D23");
				assertThat(doc.getHistory()).hasSize(1);
			});
		assertThat(mongoTemplate.findById(start.messageId().toString(), ProcessedMessageDocument.class)).isNotNull();
	}

	@Test
	void shouldRollBackTheWholeTransactionWhenACommandFails() {
		queueForDiagnosis();
		// The order is still waiting for diagnosis, so releasing the repair is refused.
		SagaMessage tooEarly = command(SagaMessageTypes.REPAIR_START, Map.of("estimatedCompletionDays", 1));

		assertThatThrownBy(() -> deliver(tooEarly)).isInstanceOf(IllegalStateException.class);

		// The idempotency record was rolled back with the failure: the broker's retry is not
		// mistaken for a duplicate.
		assertThat(mongoTemplate.findById(tooEarly.messageId().toString(), ProcessedMessageDocument.class)).isNull();
	}

	@Test
	void shouldLetAMechanicDiagnoseOverHttpAndStoreTheRepliesInTheOutbox() throws Exception {
		queueForDiagnosis();
		String mechanic = "Bearer " + token("MECHANIC");
		UUID laborId = UUID.randomUUID();

		mockMvc
			.perform(get("/api/executions").param("status", "DIAGNOSIS_QUEUED").header("Authorization", mechanic))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[?(@.serviceOrderId == '%s')]".formatted(serviceOrderId)).exists());

		mockMvc
			.perform(post("/api/executions/{id}/diagnosis/start", serviceOrderId).header("Authorization", mechanic))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("IN_DIAGNOSIS"));

		mockMvc
			.perform(
				post("/api/executions/{id}/diagnosis/complete", serviceOrderId)
					.header("Authorization", mechanic)
					.contentType(MediaType.APPLICATION_JSON)
					.content(
						"{\"mechanicDiagnosis\":\"Pastilhas\",\"labors\":[{\"mechanicServiceExternalId\":\"%s\",\"quantity\":1}]}".formatted(
								laborId
							)
					)
			)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("AWAITING_APPROVAL"))
			.andExpect(jsonPath("$.data.history[2].by").value("mecanico@teste.com"));

		assertThat(outbox())
			.extracting(OutboxMessageDocument::getMessageType)
			.containsExactlyInAnyOrder(SagaMessageTypes.DIAGNOSIS_STARTED, SagaMessageTypes.DIAGNOSIS_COMPLETED);
		assertThat(outbox())
			.filteredOn(o -> o.getMessageType().equals(SagaMessageTypes.DIAGNOSIS_COMPLETED))
			.singleElement()
			.satisfies(o -> assertThat(o.getPayload()).contains(sagaId.toString()).contains(laborId.toString()));
	}

	@Test
	void shouldRelayTheOutboxToTheBrokerOnlyOnce() throws Exception {
		queueForDiagnosis();
		mockMvc
			.perform(
				post("/api/executions/{id}/diagnosis/start", serviceOrderId).header("Authorization", "Bearer " + token("MECHANIC"))
			)
			.andExpect(status().isOk());

		outboxRelay.relayPending();
		outboxRelay.relayPending();

		verify(rabbitTemplate, times(1)).send(
			eq(RabbitTopologyConfig.EVENTS_EXCHANGE),
			eq(SagaMessageTypes.DIAGNOSIS_STARTED),
			any(Message.class)
		);
		assertThat(outbox())
			.singleElement()
			.satisfies(o -> {
				assertThat(o.getPublishedAt()).isNotNull();
				assertThat(o.getLockedUntil()).isNull();
			});
	}

	@Test
	void shouldRestrictTheApiToAuthenticatedStaff() throws Exception {
		queueForDiagnosis();

		mockMvc.perform(get("/api/executions")).andExpect(status().isForbidden());
		mockMvc
			.perform(get("/api/executions").header("Authorization", "Bearer " + token("CUSTOMER")))
			.andExpect(status().isForbidden());
		mockMvc
			.perform(
				post("/api/executions/{id}/diagnosis/start", serviceOrderId).header("Authorization", "Bearer " + token("RECEPTIONIST"))
			)
			.andExpect(status().isForbidden());
		mockMvc
			.perform(get("/api/executions/{id}", serviceOrderId).header("Authorization", "Bearer " + token("RECEPTIONIST")))
			.andExpect(status().isOk());
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}
}
