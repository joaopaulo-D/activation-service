# Activation Service

API que registra solicitações de ativação de produtos, permite consultar o status e executa a ativação em um provedor externo (`POST /provider/v1/activate`).

**Stack:** Java 21, Spring Boot 3.5 (MVC, Data JPA, Security Resource Server), PostgreSQL 16, Flyway e Maven.
**Testes:** JUnit 5, Mockito, MockMvc, Testcontainers, WireMock, Awaitility e ArchUnit.

## Como executar

Requisitos: JDK 21 e Docker.

```bash
./mvnw verify               # testes unitários + integração (Testcontainers, precisa do Docker)
docker compose up --build   # app + PostgreSQL + provedor simulado + servidor OAuth simulado
```

> Se o Docker vier do Colima, exporte antes do `verify`:
> `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` e `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.

Serviços do compose:

| Serviço | Porta | Função |
|---|---|---|
| `app` | 8080 | aplicação |
| `postgres` | 5432 (configurável via `POSTGRES_HOST_PORT`) | banco |
| `provider` | 8089 | WireMock simulando o provedor |
| `oauth` | 8081 | [mock-oauth2-server](https://github.com/navikt/mock-oauth2-server), emite JWT via `client_credentials` |

Roteiro de teste:
```bash
TOKEN=$(curl -s -X POST localhost:8081/default/token \
  -d 'grant_type=client_credentials&client_id=billing-system&client_secret=x' | jq -r .access_token)

curl -i -X POST localhost:8080/api/v1/requests \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'

curl -s localhost:8080/api/v1/requests/<id> -H "Authorization: Bearer $TOKEN"
curl -s 'localhost:8080/api/v1/requests?status=COMPLETED&customerId=CUST-10001' -H "Authorization: Bearer $TOKEN"
```

**Clientes OAuth** ([docker/oauth/oauth.json](docker/oauth/oauth.json)):
- `billing-system` e `crm-system`: leitura e escrita.
- `reporting-system`: só leitura.

**Cenários do provedor por `productCode`** ([docker/wiremock/mappings](docker/wiremock/mappings)):

| productCode | Resposta do provedor |
|---|---|
| qualquer outro | sucesso |
| `FAIL-400` | 400 |
| `FAIL-500` | 500 |
| `SLOW` | responde depois do timeout |
| `RATE-LIMIT` | 429 com `Retry-After: 30` |

**Health checks:** `/actuator/health/liveness` e `/actuator/health/readiness`.

**Configuração por variáveis de ambiente:** `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `OAUTH_ISSUER_URI`, `OAUTH_JWK_SET_URI`, `OAUTH_AUDIENCE`, `PROVIDER_BASE_URL`, `PROVIDER_API_KEY`, `PROVIDER_CONNECT_TIMEOUT`, `PROVIDER_READ_TIMEOUT` e `ACTIVATION_PROCESSOR_ENABLED`.

## API

| Rota | Escopo | Respostas |
|---|---|---|
| `POST /api/v1/requests` | `activation:write` | 201 + `Location` · 200 em retry idêntico · 400 · 401 · 403 · 409 |
| `GET /api/v1/requests/{id}` | `activation:read` | 200 · 404 |
| `GET /api/v1/requests?status=&customerId=&page=&size=` | `activation:read` | 200 paginado (`size` de 1 a 100) |

- **Validação:** `externalId`, `customerId` e `productCode` são obrigatórios, com até 64 caracteres em `[A-Za-z0-9_-]`.
- **Datas:** em UTC, formato ISO-8601.
- **`lastError`:** aparece quando houve falha na integração.

## Decisões técnicas

### Estrutura
```
api/            controller, DTOs, tratamento de erros
application/    criação/consulta, processador da fila, política de retry
domain/         entidade, status, repositório
provider/       cliente HTTP do provedor
observability/  correlation id
config/         segurança, relógio, agendamento
```
Os pacotes são organizados por responsabilidade, sem hexagonal completo, porque o domínio é pequeno. O `ArchitectureTest` garante que o domínio não depende das outras camadas.

### Estados
`PENDING → PROCESSING → COMPLETED | FAILED`

- As transições só acontecem por métodos da entidade, sem setters. Uma transição inválida lança exceção.
- Em falha temporária, a solicitação **volta para `PENDING`** com `attempts + 1` e `next_attempt_at` no futuro. Assim, `PROCESSING` sempre significa "um worker está com ela agora", o que permite detectar solicitações presas.
- `@Version` protege contra escrita concorrente.

### Duplicidade
A garantia vem de `UNIQUE (external_id)` no banco. Consultar e depois inserir não basta: duas requisições simultâneas podem não encontrar o registro e ambas inserirem.

Fluxo da criação:
1. Busca por `externalId`. Isso só otimiza o retry comum.
2. Faz o `INSERT`.
3. Se a constraint for violada, relê o registro vencedor **em outra transação**. No PostgreSQL, a transação que falhou fica abortada.

| Cenário | Resposta | Motivo |
|---|---|---|
| Mesmo `externalId` e mesmo conteúdo | **200** com o registro existente (`Idempotent-Replayed: true`) | O retry de quem chama vira sucesso, sem tratamento especial. |
| Mesmo `externalId` e conteúdo diferente | **409** | É reuso indevido do identificador, não retry. |
| Mesmo `externalId` vindo de outro cliente | **409** | Impede acessar o registro de outro consumidor. |
| Duas requisições iguais em paralelo | Uma recebe **201** e a outra **200** | A constraint decide qual vence. |

Isso é testado com 8 threads simultâneas contra o PostgreSQL real (`DuplicateRequestConcurrencyIT`).

### Integração com o provedor
**A integração é assíncrona.** O `POST` grava `PENDING` e responde na hora, então a latência e as falhas do provedor não afetam a API. O custo é que o cliente precisa consultar o status.

O `ActivationProcessor` roda a cada 1 s:
1. Numa transação curta, reserva um lote com `FOR UPDATE SKIP LOCKED` e marca `PROCESSING`.
2. Chama o provedor **fora de transação**.
3. Numa nova transação curta, grava o resultado.

Garantias desse desenho:
- **Queda entre o commit e a chamada:** a linha continua `PENDING` (a própria tabela funciona como outbox), ou fica presa em `PROCESSING` e é devolvida à fila pela reconciliação (a cada 30 s, lease de 2 min).
- **Várias instâncias:** o `SKIP LOCKED` evita que duas peguem a mesma linha.

Comportamento por retorno do provedor:

| Retorno do provedor | Status | Nova tentativa? | Motivo |
|---|---|---|---|
| 2xx | `COMPLETED` | Não | Ativado. |
| 400 / outros 4xx | `FAILED` | Não | Repetir daria o mesmo erro. |
| 5xx, 408, erro de conexão | `PENDING` | Sim, com backoff | Falha transitória. |
| Timeout | `PENDING` | Sim, com a **mesma `Idempotency-Key`** | O provedor pode ter ativado; a chave evita ativação duplicada. |
| 429/503 + `Retry-After` | `PENDING` | Sim, em `max(Retry-After, backoff)` | Respeita o limite do provedor. |
| Limite de 5 tentativas | `FAILED` | Não | `lastError` registra o motivo. |

- **Timeouts** configuráveis: conexão de 2 s e leitura de 5 s.
- **Backoff** exponencial a partir de 2 s, com teto de 5 min e jitter.
- **Circuit breaker:** não implementado (ver pendências).

### Erros
As respostas de erro usam Problem Details (RFC 9457) com `traceId`, que é o mesmo `X-Correlation-Id` dos logs.

| Situação | Status |
|---|---|
| Validação (com `errors[]`) ou JSON inválido | 400 |
| Sem token | 401 |
| Sem escopo | 403 |
| Inexistente ou de outro cliente | 404 |
| Duplicata divergente | 409 |
| Erro inesperado | 500 (stack trace só no log) |

Como a integração é assíncrona, uma falha do provedor aparece no recurso (`FAILED` + `lastError`), não como erro HTTP.

### Segurança
É um **OAuth 2.0 Resource Server com JWT**, e o fluxo é `client_credentials`, porque quem consome são sistemas.

- **Validação do token:** assinatura pelo JWKS do emissor (com rotação de chaves pelo `kid`), `iss`, `aud` (`activation-service`) e `exp`.
- **Escopos:** `activation:write` para criar, `activation:read` para consultar. O resto é negado.
- **Isolamento entre consumidores (BOLA):** o `client_id` do token (`azp`) é gravado na solicitação e filtra as consultas. Um sistema recebe 404 ao buscar a solicitação de outro.
- **Credencial do provedor:** vem por variável de ambiente (`PROVIDER_API_KEY`) e nunca é logada. Em produção ficaria num secret manager (Secrets Manager, Key Vault ou Vault), com rotação de duas chaves válidas em paralelo.
- **Testes:** 401 e 403 são testados com `jwt()` do Spring Security Test, sem servidor de autenticação.

### Observabilidade
- O correlation id vem do header (ou é gerado) e fica no MDC. Ele é **gravado com a solicitação** e restaurado no processamento assíncrono, então chega ao provedor no header `X-Correlation-Id`.
- No compose, os logs saem em JSON (formato ECS).
- Liveness e readiness, com o readiness incluindo o banco.
- `http.server.requests` e `http.client.requests` já são coletados pelo Micrometer.

### Índices
| Índice | Uso |
|---|---|
| `UNIQUE (external_id)` | duplicidade |
| `(client_id, created_at)`, `(client_id, status, created_at)`, `(client_id, customer_id, created_at)` | listagem; toda consulta filtra pelo cliente do token |
| parcial `(next_attempt_at) WHERE status='PENDING'` | polling da fila, com índice pequeno |
| parcial `(processing_started_at) WHERE status='PROCESSING'` | reconciliação |

### Testes
- **Unitários:** cobrem a lógica de decisão sem Spring.
  - domínio e transições;
  - política de retry;
  - serviço de criação, incluindo a corrida no `INSERT`;
  - classificação dos retornos do provedor contra WireMock.
- **`@WebMvcTest`:** cobre o contrato HTTP, incluindo 400, 404, 409, 500, 401 e 403.
- **Integração (Testcontainers + WireMock):** tudo que depende do comportamento real do PostgreSQL — `UNIQUE`, `SKIP LOCKED` e transação abortada após violação. O H2 não reproduziria isso. Cobre a concorrência, cada linha da tabela de integração, a reconciliação e o BOLA.
- **Agendador:** nos testes de integração ele fica desligado, e o teste chama o processador diretamente, o que deixa o resultado determinístico. Um único teste liga o agendador e usa Awaitility.

## Cenário de análise

**Sintomas:** API de 300 ms para 2,5 s, CPU em 45%, memória em 60%, queries de 50 ms para 1,2 s e provedor de 400 ms para 3 s.

**Leitura:** CPU e memória estão folgadas, então a aplicação está **esperando**, não sem recurso.

**O que investigaria primeiro:** se a chamada ao provedor está no caminho síncrono da requisição, e se o tempo das queries é de execução ou de espera por conexão.

**Como separar aplicação, banco e integração:**
- tracing (OpenTelemetry), para ver onde o tempo é gasto em cada requisição;
- métricas p95/p99 de `http.server.requests` e `http.client.requests`;
- `hikaricp.connections.pending` e `hikaricp.connections.acquire`;
- `pg_stat_statements` e `pg_stat_activity` no banco;
- thread dump durante o pico.

**Hipótese de causa comum:** a chamada ao provedor acontece dentro de uma transação ou segurando uma conexão. Com o provedor levando 3 s, o pool do Hikari esgota, e as queries passam a esperar conexão. No APM isso aparece como "query lenta", mas o PostgreSQL executa rápido.

Para confirmar, eu olharia:
- `connections.pending > 0`, acompanhando a latência do provedor;
- `pg_stat_statements` mostrando execução rápida;
- threads paradas em `HikariPool.getConnection`.

**Melhorias:**
1. Tirar a chamada externa da transação e do caminho síncrono, como este serviço faz.
2. Usar timeouts curtos e circuit breaker.
3. Revisar índices e planos de execução.
4. Ajustar o tamanho do pool. Aumentar o pool sozinho só move a fila para o banco.

**Validação depois do deploy:**
- comparar p95/p99, conexões pendentes e tempo das queries antes e depois, no mesmo horário e volume;
- publicar em canário, com alertas por SLO;
- rodar teste de carga com o provedor simulado lento.

## Produção

**Imagem (Dockerfile):**
- build multi-stage, com a imagem final só com JRE;
- jar em camadas;
- usuário não-root;
- `MaxRAMPercentage=75`;
- `HEALTHCHECK`;
- configuração por variáveis de ambiente;
- desligamento gracioso.

**Execução:**
- Kubernetes gerenciado (ou ECS) com pelo menos 2 réplicas, probes do Actuator e HPA.
- PostgreSQL gerenciado.
- Segredos injetados a partir do secret manager do provedor de nuvem.

**Várias instâncias:** a duplicidade continua garantida pela constraint, o processamento pelo `SKIP LOCKED` e a recuperação pela reconciliação. A API é stateless.

**Notificar outros serviços:** publicar `ActivationCompleted`/`ActivationFailed` num tópico (Kafka ou SNS/SQS) a partir de uma tabela de outbox de eventos gravada na mesma transação da mudança de status.

**CI/CD:**
1. `./mvnw verify`, já em `.github/workflows/ci.yml`.
2. Análise estática e de dependências.
3. Build e scan da imagem.
4. Publicação com tag do SHA.
5. Deploy em staging.
6. Promoção para produção via GitOps com canário.

## Pontos pendentes
| Item | Como faria |
|---|---|
| Rate limit por consumidor | No API Gateway, por `client_id`, ou com Bucket4j + Redis na aplicação, respondendo 429. |
| Limite de tamanho do corpo | No gateway ou ingress, ou com um filtro que responde 413. |
| Circuit breaker | Resilience4j no `ProviderClient`; com o circuito aberto, o processador não reserva lotes. |
| Métricas e tracing | `micrometer-registry-prometheus` numa porta de gerenciamento e OpenTelemetry com OTLP. |
| Reprocessar `FAILED` | Endpoint administrativo para devolver à fila as solicitações que esgotaram as tentativas. |

## Melhorias futuras
- Notificação por evento no lugar do polling do status.
- Paginação por cursor.
- Expurgo ou particionamento de solicitações antigas.
- Testes de contrato com o provedor e testes de carga.
- OpenAPI (springdoc).
- Alertas por SLO, incluindo a idade da solicitação `PENDING` mais antiga.
- Migração para Spring Boot 4.

## Uso de IA
Usei o Claude Code (Anthropic) no desenvolvimento:
- **Feito pela IA:** propôs o plano a partir do enunciado, gerou a primeira versão do código, dos testes e desta documentação.
- **Minhas escolhas:** o escopo (obrigatórios mais os diferenciais principais) e a abordagem assíncrona com outbox.
- **Minha validação:** revisei o resultado e o validei com `./mvnw verify` e com o ambiente do `docker compose`.

As decisões estão registradas em [docs/adr](docs/adr).
