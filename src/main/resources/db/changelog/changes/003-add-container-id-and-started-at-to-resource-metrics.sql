--liquibase formatted sql

--changeset viplev:003-add-container-id-and-started-at-to-resource-metrics
ALTER TABLE resource_metrics ADD COLUMN container_id TEXT;
ALTER TABLE resource_metrics ADD COLUMN started_at TIMESTAMP;

-- For SERVICE type metrics, container_id should be populated
-- For HOST type metrics, container_id can remain NULL
-- Migration note: existing SERVICE metrics will have NULL container_id until next collection cycle
