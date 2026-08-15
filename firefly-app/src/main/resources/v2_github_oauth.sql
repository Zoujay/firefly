-- Forward-only migration for existing v1 Firefly installations.
-- Relationships are logical IDs: this migration intentionally creates no
-- database foreign keys. Apply exactly once and record the checksum externally.

ALTER TABLE `firefly`.`pipeline_config`
    ADD COLUMN `branch_pattern` VARCHAR(512) NOT NULL DEFAULT '' AFTER `origin_id`;

ALTER TABLE `firefly`.`github_trigger`
    ADD COLUMN `delivery_id` VARCHAR(64) NULL AFTER `id`,
    ADD COLUMN `pipeline_id` BIGINT(20) NULL AFTER `delivery_id`,
    ADD COLUMN `pipeline_build_id` BIGINT(20) NULL AFTER `pipeline_id`,
    ADD COLUMN `github_repository_id` BIGINT(20) NULL AFTER `pipeline_build_id`,
    ADD COLUMN `event_type` VARCHAR(64) NULL AFTER `github_repo_url`,
    ADD COLUMN `action` VARCHAR(64) NULL AFTER `event_type`,
    ADD COLUMN `source_branch` VARCHAR(512) NULL AFTER `action`,
    ADD COLUMN `target_branch` VARCHAR(512) NULL AFTER `source_branch`,
    ADD COLUMN `head_sha` VARCHAR(64) NULL AFTER `target_branch`,
    ADD COLUMN `legacy_record` TINYINT(1) NOT NULL DEFAULT 1 AFTER `head_sha`,
    ADD COLUMN `created_at` DATETIME(6) NULL AFTER `legacy_record`,
    ADD UNIQUE INDEX `uidx_github_trigger_pipeline_build` (`pipeline_build_id`),
    ADD INDEX `idx_github_trigger_delivery` (`delivery_id`),
    ADD INDEX `idx_github_trigger_pipeline` (`pipeline_id`);

CREATE TABLE `firefly`.`github_oauth_state`
(
    `id`            BIGINT(20) NOT NULL AUTO_INCREMENT,
    `state`         VARCHAR(128) NOT NULL,
    `session_hash`  VARCHAR(128) NOT NULL,
    `code_verifier` VARCHAR(128) NOT NULL,
    `expires_at`    DATETIME(6) NOT NULL,
    `consumed_at`   DATETIME(6) NULL,
    `created_at`    DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_github_oauth_state` (`state`),
    INDEX `idx_github_oauth_state_expires` (`expires_at`)
);

CREATE TABLE `firefly`.`github_connection`
(
    `id`                      BIGINT(20) NOT NULL AUTO_INCREMENT,
    `public_id`               VARCHAR(64) NOT NULL,
    `singleton_key`           VARCHAR(32) NOT NULL,
    `github_user_id`          BIGINT(20) NOT NULL,
    `github_login`            VARCHAR(255) NOT NULL,
    `access_token_ciphertext` TEXT NOT NULL,
    `token_nonce`             VARBINARY(32) NOT NULL,
    `encryption_key_version`  VARCHAR(64) NOT NULL,
    `scopes`                  VARCHAR(2048) NOT NULL DEFAULT '',
    `status`                  VARCHAR(64) NOT NULL,
    `last_validated_at`       DATETIME(6) NOT NULL,
    `created_at`              DATETIME(6) NOT NULL,
    `updated_at`              DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_github_connection_public` (`public_id`),
    UNIQUE INDEX `uidx_github_connection_singleton` (`singleton_key`),
    INDEX `idx_github_connection_user` (`github_user_id`)
);

CREATE TABLE `firefly`.`github_repository_subscription`
(
    `id`                           BIGINT(20) NOT NULL AUTO_INCREMENT,
    `public_id`                    VARCHAR(64) NOT NULL,
    `connection_id`                BIGINT(20) NULL,
    `github_repository_id`         BIGINT(20) NOT NULL,
    `node_id`                      VARCHAR(255) NOT NULL,
    `owner`                        VARCHAR(255) NOT NULL,
    `repository_name`              VARCHAR(255) NOT NULL,
    `full_name`                    VARCHAR(512) NOT NULL,
    `html_url`                     VARCHAR(4096) NOT NULL,
    `clone_url`                    VARCHAR(4096) NOT NULL,
    `default_branch`               VARCHAR(512) NOT NULL,
    `webhook_id`                   BIGINT(20) NULL,
    `registration_mode`            VARCHAR(32) NOT NULL,
    `webhook_secret_ciphertext`    TEXT NOT NULL,
    `webhook_secret_nonce`         VARBINARY(32) NOT NULL,
    `webhook_secret_key_version`   VARCHAR(64) NOT NULL,
    `events`                       JSON NOT NULL,
    `status`                       VARCHAR(64) NOT NULL,
    `last_error`                   VARCHAR(2048) NOT NULL DEFAULT '',
    `created_at`                   DATETIME(6) NOT NULL,
    `updated_at`                   DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_github_subscription_public` (`public_id`),
    UNIQUE INDEX `uidx_github_subscription_repository` (`github_repository_id`),
    UNIQUE INDEX `uidx_github_subscription_webhook` (`webhook_id`),
    INDEX `idx_github_subscription_connection` (`connection_id`),
    INDEX `idx_github_subscription_status` (`status`)
);

CREATE TABLE `firefly`.`github_trigger_config`
(
    `id`                   BIGINT(20) NOT NULL AUTO_INCREMENT,
    `pipeline_id`          BIGINT(20) NOT NULL,
    `subscription_id`      BIGINT(20) NOT NULL,
    `enabled`              TINYINT(1) NOT NULL DEFAULT 1,
    `disabled_reason`      VARCHAR(255) NOT NULL DEFAULT '',
    `events`               JSON NOT NULL,
    `pull_request_actions` JSON NOT NULL,
    `ignore_delete_push`   TINYINT(1) NOT NULL DEFAULT 1,
    `created_at`           DATETIME(6) NOT NULL,
    `updated_at`           DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_github_trigger_config_pipeline` (`pipeline_id`),
    INDEX `idx_github_trigger_config_subscription` (`subscription_id`, `enabled`)
);

CREATE TABLE `firefly`.`github_webhook_delivery`
(
    `id`                     BIGINT(20) NOT NULL AUTO_INCREMENT,
    `delivery_id`            VARCHAR(64) NOT NULL,
    `subscription_id`        BIGINT(20) NOT NULL,
    `event_type`             VARCHAR(64) NOT NULL,
    `action`                 VARCHAR(64) NULL,
    `repository_id`          BIGINT(20) NOT NULL,
    `payload`                LONGTEXT NOT NULL,
    `status`                 VARCHAR(32) NOT NULL,
    `processing_attempt`     INT NOT NULL DEFAULT 0,
    `processor_id`           VARCHAR(128) NOT NULL DEFAULT '',
    `processing_started_at`  DATETIME(6) NULL,
    `next_retry_at`          DATETIME(6) NULL,
    `last_error`             VARCHAR(2048) NOT NULL DEFAULT '',
    `received_at`            DATETIME(6) NOT NULL,
    `processing_finished_at` DATETIME(6) NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_github_delivery` (`delivery_id`),
    INDEX `idx_github_delivery_subscription` (`subscription_id`, `received_at`),
    INDEX `idx_github_delivery_status_retry` (`status`, `next_retry_at`)
);

CREATE TABLE `firefly`.`github_delivery_pipeline`
(
    `id`                 BIGINT(20) NOT NULL AUTO_INCREMENT,
    `delivery_id`        VARCHAR(64) NOT NULL,
    `pipeline_id`        BIGINT(20) NOT NULL,
    `pipeline_build_id`  BIGINT(20) NULL,
    `status`             VARCHAR(32) NOT NULL,
    `processing_attempt` INT NOT NULL DEFAULT 0,
    `last_error`         VARCHAR(2048) NOT NULL DEFAULT '',
    `created_at`         DATETIME(6) NOT NULL,
    `updated_at`         DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_github_delivery_pipeline` (`delivery_id`, `pipeline_id`),
    INDEX `idx_github_delivery_pipeline_status` (`status`, `updated_at`)
);
