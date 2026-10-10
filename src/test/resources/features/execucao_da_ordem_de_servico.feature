# language: pt
Funcionalidade: Execução da ordem de serviço
  Como equipe de mecânicos da oficina
  Quero uma fila com as ordens que aguardam diagnóstico e reparo
  Para trabalhar na ordem de chegada e manter a oficina informada do andamento

  Cenário: Fluxo completo, do diagnóstico à conclusão do reparo
    Quando a saga envia a ordem de serviço do veículo "ABC1D23" para diagnóstico
    Então a ordem de trabalho fica "DIAGNOSIS_QUEUED"

    Quando o mecânico inicia o diagnóstico
    Então a ordem de trabalho fica "IN_DIAGNOSIS"
    E a saga é informada de "execution.diagnosis.started"

    Quando o mecânico conclui o diagnóstico listando 1 serviço e 2 unidades de uma peça
    Então a ordem de trabalho fica "AWAITING_APPROVAL"
    E a saga recebe o diagnóstico com 1 serviço e 1 peça para precificar

    Quando a saga libera o reparo após aprovação e pagamento
    Então a ordem de trabalho fica "REPAIR_QUEUED"

    Quando o mecânico inicia o reparo
    E o mecânico conclui o reparo
    Então a ordem de trabalho fica "COMPLETED"
    E a saga é informada de "execution.repair.completed"
    E o histórico registra as etapas "DIAGNOSIS_QUEUED, IN_DIAGNOSIS, AWAITING_APPROVAL, REPAIR_QUEUED, IN_REPAIR, COMPLETED"

  Cenário: A fila atende na ordem de chegada
    Dado a ordem de serviço do veículo "AAA1A11" na fila de diagnóstico
    E a ordem de serviço do veículo "BBB2B22" na fila de diagnóstico
    E a ordem de serviço do veículo "CCC3C33" na fila de diagnóstico
    Quando o mecânico consulta a fila de diagnóstico
    Então a fila apresenta os veículos "AAA1A11, BBB2B22, CCC3C33"

  Cenário: O reparo só começa depois de liberado pela saga
    Dado uma ordem de trabalho com o diagnóstico concluído
    Quando o mecânico tenta iniciar o reparo
    Então a operação é recusada
    E a ordem de trabalho fica "AWAITING_APPROVAL"

  Cenário: Mecânico informa que o reparo é inviável
    Dado uma ordem de trabalho em reparo
    Quando o mecânico informa que o reparo falhou por "Disco de freio trincado"
    Então a ordem de trabalho fica "FAILED"
    E a saga é informada de "execution.repair.failed" com o motivo "Disco de freio trincado"

  Cenário: A saga devolve um diagnóstico que não pôde ser precificado
    Dado uma ordem de trabalho com o diagnóstico concluído
    Quando a saga reabre o diagnóstico por "Peça não encontrada no estoque"
    Então a ordem de trabalho fica "IN_DIAGNOSIS"
    E o histórico registra o motivo "Peça não encontrada no estoque"

  Cenário: Compensação cancela o trabalho
    Dado uma ordem de trabalho com o diagnóstico concluído
    Quando a saga cancela a ordem de trabalho por "Orçamento recusado pelo cliente"
    Então a ordem de trabalho fica "CANCELED"
    E a ordem deixa de aparecer na fila

  Cenário: Um comando entregue duas vezes não enfileira a ordem duas vezes
    Quando o mesmo pedido de diagnóstico é entregue duas vezes
    Então existe uma única ordem de trabalho para a ordem de serviço
