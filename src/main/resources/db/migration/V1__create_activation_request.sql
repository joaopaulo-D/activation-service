CREATE TABLE activation_request (
    id                    UUID         PRIMARY KEY,
    external_id           VARCHAR(64)  NOT NULL,
    customer_id           VARCHAR(64)  NOT NULL,
    product_code          VARCHAR(64)  NOT NULL,
    client_id             VARCHAR(128) NOT NULL,
    correlation_id        VARCHAR(64),
    status                VARCHAR(16)  NOT NULL,
    attempts              INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at       TIMESTAMPTZ,
    processing_started_at TIMESTAMPTZ,
    last_error            VARCHAR(255),
    version               BIGINT       NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_activation_request_external_id UNIQUE (external_id),
    CONSTRAINT ck_activation_request_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

-- Listagem: toda consulta é restrita ao client_id do chamador (isolamento entre consumidores)
-- e ordenada por created_at, então o client_id lidera os índices.
CREATE INDEX ix_activation_request_client_created
    ON activation_request (client_id, created_at DESC);
CREATE INDEX ix_activation_request_client_status_created
    ON activation_request (client_id, status, created_at DESC);
CREATE INDEX ix_activation_request_client_customer_created
    ON activation_request (client_id, customer_id, created_at DESC);

-- Polling da fila: índices parciais pequenos, contendo apenas as linhas em trânsito.
CREATE INDEX ix_activation_request_pending_due
    ON activation_request (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_activation_request_processing_started
    ON activation_request (processing_started_at) WHERE status = 'PROCESSING';
