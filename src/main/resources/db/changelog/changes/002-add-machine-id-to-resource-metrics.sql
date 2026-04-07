--liquibase formatted sql

--changeset viplev:002-add-machine-id-to-resource-metrics
ALTER TABLE resource_metrics ADD COLUMN machine_id VARCHAR(255);
