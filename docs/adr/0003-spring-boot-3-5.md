# ADR 0003 — Spring Boot 3.5 e Java 21

**Status:** aceita

## Contexto
O desafio pede Java 17 ou 21, com Spring Boot 3.x como diferencial. O Spring Boot 4 (Spring Framework 7, Jackson 3, starters modularizados) já está disponível, mas traz mudanças de pacote e de dependências que ainda não são o padrão na maior parte dos times.

## Decisão
Java 21 (records, sealed interfaces com `switch` por pattern matching, virtual threads no processamento do lote) e Spring Boot **3.5**, a última linha 3.x.

## Consequências
- A stack é a mais difundida hoje e está alinhada com a vaga e com o enunciado.
- A migração para o Boot 4 está registrada como melhoria futura. O código não usa APIs removidas, mas exigiria ajustar imports de teste e a dependência do Jackson.
