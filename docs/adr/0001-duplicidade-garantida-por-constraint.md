# ADR 0001 — Duplicidade garantida por constraint UNIQUE no banco

**Status:** aceita

## Contexto
O `externalId` identifica uma solicitação do sistema de origem, e o serviço não pode criar dois registros com o mesmo valor, inclusive quando requisições iguais chegam ao mesmo tempo e em instâncias diferentes. Uma verificação do tipo "consultar e depois inserir" não é atômica: duas transações podem não encontrar o registro e inserir as duas.

## Decisão
- `UNIQUE (external_id)` na tabela é a única fonte da verdade.
- A aplicação consulta antes apenas como otimização do caso de retry. Na violação da constraint (`DataIntegrityViolationException`), ela relê o registro vencedor em outra transação, porque no PostgreSQL a transação que falhou fica abortada.
- Retry com o mesmo conteúdo → 200 com o registro existente. Conteúdo diferente ou outro cliente → 409.

## Alternativas descartadas
- **Lock pessimista ou advisory lock por `externalId`:** resolve o problema, mas adiciona coordenação, e a constraint já resolve com menos código.
- **`INSERT … ON CONFLICT DO NOTHING` nativo:** evita a exceção, mas acopla o código ao dialeto do PostgreSQL e foge do JPA sem ganho relevante neste volume.
- **Lock distribuído (Redis):** introduziria uma dependência e um ponto de falha a mais, e ainda seria menos confiável que o banco.

## Consequências
- O comportamento é correto com qualquer número de instâncias.
- A perdedora da corrida paga uma exceção e uma leitura extra, o que é aceitável porque o caso é raro.
