package org.project.mechanic_shop.execution.bdd;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.pt.Dado;
import io.cucumber.java.pt.Entao;
import io.cucumber.java.pt.Quando;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.project.mechanic_shop.execution.application.saga.SagaMessage;
import org.project.mechanic_shop.execution.application.saga.SagaMessageTypes;
import org.project.mechanic_shop.execution.application.saga.StartRepairPayload;
import org.project.mechanic_shop.execution.domain.dto.WorkOrderShortDto;
import org.project.mechanic_shop.execution.domain.entities.work_order.WorkOrder;
import org.project.mechanic_shop.execution.domain.enums.WorkOrderStatusEnum;
import org.project.mechanic_shop.execution.support.ExecutionTestHarness;
import org.springframework.data.domain.PageRequest;

/**
 * Steps of the Execution scenarios. The Execution use cases are the system under test; the steps
 * act as the Saga orchestrator (sending its commands) and as the mechanic.
 */
public class ExecutionSteps {

	private final ExecutionTestHarness harness = new ExecutionTestHarness();
	private List<WorkOrder> queue;
	private Throwable failure;

	// --------------------------------------------------------------------------------------- Dado

	@Dado("a ordem de serviço do veículo {string} na fila de diagnóstico")
	public void ordemNaFila(String licensePlate) {
		harness.queueAnotherOrder(licensePlate);
	}

	@Dado("uma ordem de trabalho com o diagnóstico concluído")
	public void diagnosticoConcluido() {
		harness.queueDiagnosis();
		mecanicoIniciaDiagnostico();
		mecanicoConcluiDiagnostico(1, 2);
	}

	@Dado("uma ordem de trabalho em reparo")
	public void emReparo() {
		diagnosticoConcluido();
		sagaLiberaReparo();
		mecanicoIniciaReparo();
	}

	// ------------------------------------------------------------------------------------- Quando

	@Quando("a saga envia a ordem de serviço do veículo {string} para diagnóstico")
	public void sagaEnviaParaDiagnostico(String licensePlate) {
		harness.receive(SagaMessageTypes.DIAGNOSIS_START, harness.startDiagnosisPayload(licensePlate));
	}

	@Quando("o mesmo pedido de diagnóstico é entregue duas vezes")
	public void pedidoEntregueDuasVezes() {
		SagaMessage command = harness.command(
			harness.serviceOrderId,
			SagaMessageTypes.DIAGNOSIS_START,
			harness.startDiagnosisPayload("ABC1D23")
		);
		harness.commandHandler.handle(command);
		harness.commandHandler.handle(command);
	}

	@Quando("o mecânico inicia o diagnóstico")
	public void mecanicoIniciaDiagnostico() {
		harness.workOrderService.startDiagnosis(harness.serviceOrderId, ExecutionTestHarness.MECHANIC);
	}

	@Quando("o mecânico conclui o diagnóstico listando {int} serviço e {int} unidades de uma peça")
	public void mecanicoConcluiDiagnostico(int labors, int partUnits) {
		assertThat(labors).isEqualTo(1);
		assertThat(partUnits).isEqualTo(2);
		harness.workOrderService.completeDiagnosis(
			harness.serviceOrderId,
			"Pastilhas dianteiras desgastadas",
			harness.diagnosisItems(),
			ExecutionTestHarness.MECHANIC
		);
	}

	@Quando("a saga libera o reparo após aprovação e pagamento")
	public void sagaLiberaReparo() {
		harness.receive(SagaMessageTypes.REPAIR_START, new StartRepairPayload(1));
	}

	@Quando("o mecânico inicia o reparo")
	public void mecanicoIniciaReparo() {
		harness.workOrderService.startRepair(harness.serviceOrderId, ExecutionTestHarness.MECHANIC);
	}

	@Quando("o mecânico tenta iniciar o reparo")
	public void mecanicoTentaIniciarReparo() {
		try {
			mecanicoIniciaReparo();
		} catch (RuntimeException e) {
			failure = e;
		}
	}

	@Quando("o mecânico conclui o reparo")
	public void mecanicoConcluiReparo() {
		harness.workOrderService.completeRepair(harness.serviceOrderId, ExecutionTestHarness.MECHANIC);
	}

	@Quando("o mecânico informa que o reparo falhou por {string}")
	public void mecanicoInformaFalha(String reason) {
		harness.workOrderService.failRepair(harness.serviceOrderId, reason, ExecutionTestHarness.MECHANIC);
	}

	@Quando("a saga reabre o diagnóstico por {string}")
	public void sagaReabreDiagnostico(String reason) {
		harness.receive(SagaMessageTypes.DIAGNOSIS_REOPEN, Map.of("reason", reason));
	}

	@Quando("a saga cancela a ordem de trabalho por {string}")
	public void sagaCancela(String reason) {
		harness.receive(SagaMessageTypes.WORK_ORDER_CANCEL, Map.of("reason", reason));
	}

	@Quando("o mecânico consulta a fila de diagnóstico")
	public void mecanicoConsultaFila() {
		queue = harness.workOrderService.findQueue(WorkOrderStatusEnum.DIAGNOSIS_QUEUED, PageRequest.of(0, 10)).getContent();
	}

	// -------------------------------------------------------------------------------------- Então

	@Entao("a ordem de trabalho fica {string}")
	public void ordemDeTrabalhoFica(String status) {
		assertThat(harness.workOrder().getStatus()).isEqualTo(WorkOrderStatusEnum.valueOf(status));
	}

	@Entao("a saga é informada de {string}")
	public void sagaInformada(String type) {
		var event = harness.lastEvent(type);
		assertThat(event.sagaId()).isEqualTo(harness.sagaId);
		assertThat(event.serviceOrderId()).isEqualTo(harness.serviceOrderId);
	}

	@Entao("a saga é informada de {string} com o motivo {string}")
	public void sagaInformadaComMotivo(String type, String reason) {
		assertThat(harness.lastEvent(type).payload()).containsEntry("reason", reason);
	}

	@Entao("a saga recebe o diagnóstico com {int} serviço e {int} peça para precificar")
	@SuppressWarnings("unchecked")
	public void sagaRecebeDiagnostico(int labors, int parts) {
		Map<String, Object> payload = harness.lastEvent(SagaMessageTypes.DIAGNOSIS_COMPLETED).payload();

		List<Map<String, Object>> sentLabors = (List<Map<String, Object>>) payload.get("labors");
		List<Map<String, Object>> sentParts = (List<Map<String, Object>>) payload.get("parts");
		assertThat(sentLabors).hasSize(labors);
		assertThat(sentParts).hasSize(parts);
		assertThat(sentLabors.get(0)).containsEntry("mechanicServiceExternalId", harness.laborId.toString());
		assertThat(sentParts.get(0)).containsEntry("partExternalId", harness.partId.toString()).containsEntry("quantity", 2);
		assertThat(payload).containsEntry("diagnosis", "Pastilhas dianteiras desgastadas");
	}

	@Entao("o histórico registra as etapas {string}")
	public void historicoRegistraEtapas(String steps) {
		assertThat(harness.workOrder().getHistory())
			.extracting(entry -> entry.status().name())
			.containsExactlyElementsOf(Arrays.asList(steps.split(",\\s*")));
	}

	@Entao("o histórico registra o motivo {string}")
	public void historicoRegistraMotivo(String reason) {
		assertThat(harness.workOrder().getHistory().getLast().note()).isEqualTo(reason);
	}

	@Entao("a fila apresenta os veículos {string}")
	public void filaApresenta(String licensePlates) {
		assertThat(queue)
			.extracting(w -> WorkOrderShortDto.from(w).licensePlate())
			.containsExactlyElementsOf(Arrays.asList(licensePlates.split(",\\s*")));
	}

	@Entao("a operação é recusada")
	public void operacaoRecusada() {
		assertThat(failure).isInstanceOf(IllegalStateException.class);
	}

	@Entao("a ordem deixa de aparecer na fila")
	public void ordemSaiDaFila() {
		assertThat(harness.workOrderService.findQueue(null, PageRequest.of(0, 10)).getContent()).isEmpty();
	}

	@Entao("existe uma única ordem de trabalho para a ordem de serviço")
	public void umaUnicaOrdem() {
		assertThat(harness.workOrderRepository.count()).isEqualTo(1);
	}
}
