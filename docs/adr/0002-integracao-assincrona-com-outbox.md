# ADR 0002 — Integração assíncrona usando a tabela de solicitações como outbox

**Status:** aceita

## Contexto
Após criar a solicitação, o serviço precisa chamar o provedor de ativação. O provedor pode ficar lento, falhar ou responder com timeout ambíguo. Fazer a chamada no caminho síncrono do `POST` acopla a latência da API à do provedor. Fazer a chamada dentro de uma transação aberta segura conexões do pool durante a chamada remota. E chamar o provedor logo após o commit, em memória, perde a solicitação se a instância cair entre o commit e a chamada.

## Decisão
- O `POST` só grava a solicitação como `PENDING` (com `next_attempt_at`) e responde 201.
- Um processador agendado reserva lotes com `SELECT … FOR UPDATE SKIP LOCKED`, marca `PROCESSING` e faz o commit; chama o provedor **fora de transação**; aplica o resultado em uma nova transação curta.
- Falha transitória → volta a `PENDING`, com backoff exponencial e jitter (respeitando `Retry-After`) até o limite de tentativas.
- Uma reconciliação devolve à fila solicitações em `PROCESSING` com lease expirado.
- Toda chamada envia `Idempotency-Key = id da solicitação`, a mesma em todas as tentativas.

## Alternativas descartadas
- **Síncrono:** é mais simples para o cliente, mas a latência e as falhas do provedor passam a afetar diretamente a API.
- **`@Async` ou fila em memória:** perde trabalho quando a instância cai.
- **Broker (Kafka/RabbitMQ) desde já:** é mais infraestrutura sem necessidade no escopo atual. Mesmo assim, a escrita atômica no banco continuaria precisando de outbox.

## Consequências
- Entrega *at-least-once*: o provedor pode receber a mesma solicitação mais de uma vez, e a chave de idempotência é o que evita ativação duplicada.
- O cliente acompanha o status por consulta, até existir a notificação por evento.
- Polling a cada 1 s, com custo baixo graças ao índice parcial em `PENDING`.
