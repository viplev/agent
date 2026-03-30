--liquibase formatted sql

--changeset viplev:001-create-resource-metrics-table
CREATE TABLE resource_metrics (
    id                  VARCHAR(36)     NOT NULL PRIMARY KEY,
    collected_at        TIMESTAMP       NOT NULL,
    target_type         VARCHAR(10)     NOT NULL,
    target_name         VARCHAR(255)    NOT NULL,
    cpu_percentage      DOUBLE,
    memory_usage_bytes  DOUBLE,
    memory_limit_bytes  DOUBLE,
    network_in_bytes    DOUBLE,
    network_out_bytes   DOUBLE,
    block_in_bytes      DOUBLE,
    block_out_bytes     DOUBLE,
    flushed             BOOLEAN         NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_resource_metrics_flushed_collected
    ON resource_metrics (flushed, collected_at);
