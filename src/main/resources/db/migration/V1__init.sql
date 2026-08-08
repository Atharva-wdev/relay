CREATE TABLE workflows (
  id VARCHAR(128) PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  status VARCHAR(32) NOT NULL,
  definition_json JSON NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE runs (
  run_id VARCHAR(128) PRIMARY KEY,
  workflow_id VARCHAR(128) NOT NULL,
  definition_snapshot JSON NOT NULL,
  status VARCHAR(32) NOT NULL,
  trigger_type VARCHAR(32) NOT NULL,
  input_json JSON NOT NULL,
  current_node_id VARCHAR(128) NULL,
  steps_executed INT NOT NULL DEFAULT 0,
  ai_tokens_used INT NOT NULL DEFAULT 0,
  error_json JSON NULL,
  started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at TIMESTAMP NULL,
  INDEX idx_runs_workflow(workflow_id),
  INDEX idx_runs_status(status)
);

CREATE TABLE run_steps (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id VARCHAR(128) NOT NULL,
  node_id VARCHAR(128) NOT NULL,
  node_type VARCHAR(64) NOT NULL,
  sequence_no INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  attempt INT NOT NULL,
  resolved_input_json JSON NULL,
  output_json JSON NULL,
  tokens_prompt INT NULL,
  tokens_completion INT NULL,
  idempotency_key VARCHAR(255) NULL,
  started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  duration_ms BIGINT NULL,
  error_message TEXT NULL,
  INDEX idx_steps_run_seq(run_id, sequence_no)
);

CREATE TABLE approvals (
  id VARCHAR(128) PRIMARY KEY,
  run_id VARCHAR(128) NOT NULL,
  node_id VARCHAR(128) NOT NULL,
  message TEXT NOT NULL,
  status VARCHAR(32) NOT NULL,
  decided_by VARCHAR(128) NULL,
  decided_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_approvals_status(status),
  INDEX idx_approvals_run(run_id)
);

CREATE TABLE queue_jobs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'queued',
  available_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  leased_until TIMESTAMP NULL,
  attempts INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_queue_run(run_id),
  INDEX idx_queue_poll(status, available_at, leased_until)
);