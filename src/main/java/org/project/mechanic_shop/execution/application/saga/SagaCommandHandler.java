package org.project.mechanic_shop.execution.application.saga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.project.mechanic_shop.execution.application.ports.ProcessedMessagePort;
import org.project.mechanic_shop.execution.application.services.WorkOrderService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Executes the commands the Saga orchestrator sends to the Execution Service.
 *
 * <p>The idempotency record and the state change happen in one MongoDB transaction. A redelivered
 * command is detected and ignored.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SagaCommandHandler {

	private final ProcessedMessagePort processedMessages;
	private final WorkOrderService workOrderService;
	private final ObjectMapper objectMapper;

	@Transactional
	public void handle(SagaMessage message) {
		if (!processedMessages.registerIfNew(message.messageId(), message.type())) {
			log.info("Duplicated message {} ({}) ignored.", message.messageId(), message.type());
			return;
		}

		log.info("Handling command {} for Service Order {}", message.type(), message.serviceOrderId());

		switch (message.type()) {
			case SagaMessageTypes.DIAGNOSIS_START -> workOrderService.queueForDiagnosis(
				message.sagaId(),
				message.serviceOrderId(),
				objectMapper.treeToValue(message.payload(), StartDiagnosisPayload.class)
			);
			case SagaMessageTypes.DIAGNOSIS_REOPEN -> workOrderService.reopenDiagnosis(
				message.serviceOrderId(),
				reasonOf(message)
			);
			case SagaMessageTypes.REPAIR_START -> workOrderService.queueRepair(
				message.serviceOrderId(),
				objectMapper.treeToValue(message.payload(), StartRepairPayload.class).estimatedCompletionDays()
			);
			case SagaMessageTypes.WORK_ORDER_CANCEL -> workOrderService.cancel(message.serviceOrderId(), reasonOf(message));
			default -> log.warn("Unknown command type '{}' ignored.", message.type());
		}
	}

	private String reasonOf(SagaMessage message) {
		if (message.payload() == null || message.payload().isNull()) return null;
		return objectMapper.treeToValue(message.payload(), ReasonPayload.class).reason();
	}
}
