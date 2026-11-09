# Arquitetura do Execution Service

Microsserviço do chão de oficina. Mantém a fila de trabalho, registra o diagnóstico do mecânico, acompanha o reparo e comunica a finalização ao OS Service. Participa da Saga da ordem de serviço, orquestrada pelo OS Service. A visão do sistema inteiro (os três serviços, a Saga e a implantação) está no repositório do OS Service, em `docs/architecture/`.

## Contexto

```mermaid
flowchart LR
    mechanic["Mecânico"]
    staff["Recepção / admin"]
    kong["Kong<br/>/api/executions/**"]
    mq{{"RabbitMQ"}}
    os["OS Service<br/>(orquestrador)"]

    subgraph execution["Execution Service"]
        app["Spring Boot"]
        db[("MongoDB (replica set)<br/>work_orders,<br/>outbox_messages,<br/>processed_messages")]
    end

    mechanic -->|"diagnóstico e reparo"| kong
    staff -->|"consulta a fila"| kong
    kong --> app
    os -->|"comandos execution.*<br/>(fila execution.commands)"| mq --> app
    app -->|"respostas execution.*<br/>(saga.events)"| mq --> os
    app --> db
```

O serviço não chama nenhum outro serviço e não lê nenhum outro banco. Os dados do veículo e da reclamação chegam no comando `execution.diagnosis.start`. Serviços e peças do diagnóstico são guardados só como referência e quantidade: nomes, preços e estoque pertencem ao OS Service, que precifica o orçamento.

## Componentes

```mermaid
flowchart LR
    kong["Kong"]
    mq{{"RabbitMQ"}}
    db[("MongoDB")]

    subgraph app["Execution Service"]
        sec["Segurança<br/>JwtAuthenticationFilter,<br/>TokenVerifier"]
        ctrl["WorkOrderController"]
        listener["SagaCommandListener<br/>(idempotente)"]
        handler["SagaCommandHandler"]
        svc["WorkOrderServiceImpl"]
        domain["WorkOrder<br/>(regras de transição)"]
        pub["OutboxSagaEventPublisher"]
        relay["OutboxRelay"]
        repo["Adapters MongoDB"]
    end

    kong --> sec --> ctrl --> svc
    mq -->|"execution.commands"| listener --> handler --> svc
    svc --> domain
    svc --> pub --> repo
    svc --> repo
    relay -->|"lê outbox"| repo
    relay -->|"saga.events"| mq
    repo --> db
```

| Componente | Responsabilidade |
|---|---|
| `SagaCommandListener` + `SagaCommandHandler` | Consome `execution.commands`, descarta reentregas (`processed_messages`) e despacha para o caso de uso |
| `WorkOrderServiceImpl` | Casos de uso da fila: enfileirar, iniciar e concluir diagnóstico, reabrir, enfileirar, iniciar, concluir ou declarar inviável o reparo, cancelar |
| `WorkOrder` | Agregado com as regras de transição de estado; cada mudança acrescenta uma entrada ao histórico |
| `OutboxSagaEventPublisher` + `OutboxRelay` | Grava a resposta da Saga na mesma transação MongoDB da mudança de estado e publica em `saga.events` |
| Segurança | Valida o JWT emitido pelo OS Service; as rotas são da equipe (admin, recepção e mecânico) |

## Modelo de documentos

```mermaid
erDiagram
    work_orders ||--|| vehicle : "embutido"
    work_orders ||--o{ items : "embutidos"
    work_orders ||--o{ history : "embutido, só cresce"
    work_orders {
        string _id PK
        string serviceOrderId UK
        string sagaId
        string customerName
        string customerComplaint
        int odometerReading
        string assignedMechanicEmail
        string status
        string diagnosis
        int estimatedCompletionDays
        date queuedAt "índice (status, queuedAt)"
    }
    vehicle {
        string licensePlate
        string brand
        string model
        int year
    }
    items {
        string type "LABOR ou PART"
        string referenceId
        int quantity
    }
    history {
        string status
        date at
        string by
        string note
    }
    outbox_messages {
        string _id PK
        string exchange
        string routingKey
        string messageType
        string payload
        date createdAt
        date publishedAt "nulo = pendente"
        date lockedUntil
    }
    processed_messages {
        string messageId PK
        string messageType
        date processedAt
    }
```

## Ciclo de vida da ordem de trabalho

```mermaid
stateDiagram-v2
    [*] --> DIAGNOSIS_QUEUED: execution.diagnosis.start
    DIAGNOSIS_QUEUED --> IN_DIAGNOSIS: mecânico inicia (diagnosis.started)
    IN_DIAGNOSIS --> AWAITING_APPROVAL: mecânico conclui (diagnosis.completed)
    AWAITING_APPROVAL --> IN_DIAGNOSIS: execution.diagnosis.reopen
    AWAITING_APPROVAL --> REPAIR_QUEUED: execution.repair.start (orçamento aprovado e pago)
    REPAIR_QUEUED --> IN_REPAIR: mecânico inicia (repair.started)
    IN_REPAIR --> COMPLETED: mecânico conclui (repair.completed)
    REPAIR_QUEUED --> FAILED: reparo inviável (repair.failed)
    IN_REPAIR --> FAILED: reparo inviável (repair.failed)
    DIAGNOSIS_QUEUED --> CANCELED: execution.workorder.cancel
    IN_DIAGNOSIS --> CANCELED: execution.workorder.cancel
    AWAITING_APPROVAL --> CANCELED: execution.workorder.cancel
    REPAIR_QUEUED --> CANCELED: execution.workorder.cancel
    IN_REPAIR --> CANCELED: execution.workorder.cancel
    COMPLETED --> [*]
    FAILED --> [*]
    CANCELED --> [*]
```

O reparo só pode começar depois que a Saga libera, isto é, depois do orçamento aprovado e pago. O mecânico não consegue pular essa etapa pela API. O cancelamento só tem efeito em ordens ativas; em `COMPLETED`, `FAILED` ou `CANCELED`, é ignorado.

## Participação na Saga

| Comando recebido | Ação | Resposta |
|---|---|---|
| `execution.diagnosis.start` | Coloca a ordem na fila de diagnóstico | — |
| — (mecânico) | Inicia e conclui o diagnóstico, com serviços e peças | `execution.diagnosis.started`, `execution.diagnosis.completed` |
| `execution.diagnosis.reopen` | Devolve o diagnóstico ao mecânico, com o motivo | — |
| `execution.repair.start` | Coloca a ordem na fila de reparo | — |
| — (mecânico) | Inicia, conclui ou declara inviável o reparo | `execution.repair.started`, `execution.repair.completed` / `execution.repair.failed` |
| `execution.workorder.cancel` | Cancela o trabalho (compensação) | — |

```mermaid
sequenceDiagram
    autonumber
    participant OS as OS Service
    participant EX as Execution Service
    actor M as Mecânico

    OS->>EX: execution.diagnosis.start
    M->>EX: GET /api/executions?status=DIAGNOSIS_QUEUED
    M->>EX: POST .../diagnosis/start
    EX-->>OS: execution.diagnosis.started
    M->>EX: POST .../diagnosis/complete
    EX-->>OS: execution.diagnosis.completed
    Note over OS: orçamento, aprovação e pagamento
    OS->>EX: execution.repair.start
    M->>EX: POST .../repair/start
    EX-->>OS: execution.repair.started
    M->>EX: POST .../repair/complete
    EX-->>OS: execution.repair.completed
```

## Decisões

- **MongoDB (o banco NoSQL do sistema):** cada ordem de trabalho é um documento autocontido, com veículo, itens e histórico embutidos, e o histórico só cresce. Não há junções: a fila é uma consulta por status ordenada pela data de entrada, atendida pelo índice `(status, queuedAt)`.
- **Replica set:** a outbox usa transações multi-documento do MongoDB, que exigem replica set. O Atlas sempre é um; localmente o container sobe como replica set de um nó.
- **Outbox com trava:** `lockedUntil` impede que duas réplicas publiquem a mesma mensagem ao mesmo tempo.
- **Snapshot em vez de consulta:** os dados do veículo são copiados no comando. O serviço continua funcionando mesmo com o OS Service fora do ar.

## Observabilidade

Logs estruturados em JSON (perfil `prod`), `correlationId` lido das mensagens e propagado no MDC e no New Relic, e eventos `MechanicShopSagaFailure` e `MechanicShopIntegrationFailure` para falhas de passo e de integração.
