-- Complete Firefly schema for new installations.
-- All tables and Stage ordering constraints are defined in this file.

CREATE TABLE `firefly`.`pipeline_config`
(
    `id`             BIGINT(20) NOT NULL AUTO_INCREMENT,
    `pipeline_uuid`  VARCHAR(64) NOT NULL DEFAULT '',
    `pipeline_name`  VARCHAR(64) NOT NULL DEFAULT '',
    `trigger_mode`   VARCHAR(64) NOT NULL,
    `trigger_match`  VARCHAR(64) NOT NULL,
    `trigger_origin` VARCHAR(64) NOT NULL,
    `origin_id`      BIGINT(20) NOT NULL DEFAULT -1,
    `branch_pattern` VARCHAR(512) NOT NULL DEFAULT '',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_pipeline_uuid` (`pipeline_uuid`),
    INDEX            `idx_pipeline_name` (`pipeline_name`),
    INDEX            `idx_trigger_origin` (`origin_id`, `trigger_origin`)
);


CREATE TABLE `firefly`.`volcano_config`
(
    `id`          BIGINT(20) NOT NULL AUTO_INCREMENT,
    `pipeline_id` BIGINT(20) NOT NULL,
    `ak`          VARCHAR(1024) NOT NULL,
    `sk`          VARCHAR(1024) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_pipeline_id` (`pipeline_id`)
);


CREATE TABLE `firefly`.`stage_config`
(
    `id`          BIGINT(20) NOT NULL AUTO_INCREMENT,
    `pipeline_id` BIGINT(20) NOT NULL,
    `stage_order` INT        NOT NULL,
    `stage_uuid`  VARCHAR(64) NOT NULL,
    `stage_name`  VARCHAR(64) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_stage_uuid` (`stage_uuid`),
    UNIQUE INDEX `uidx_pipeline_stage_order` (`pipeline_id`, `stage_order`),
    INDEX         `idx_stage_name` (`stage_name`),
    INDEX         `idx_pipeline_id` (`pipeline_id`)
);


CREATE TABLE `firefly`.`job_config`
(
    `id`          BIGINT(20) NOT NULL AUTO_INCREMENT,
    `stage_id`    BIGINT(20) NOT NULL,
    `job_uuid`    VARCHAR(64) NOT NULL,
    `job_name`    VARCHAR(64) NOT NULL,
    `plugin_type` VARCHAR(64) NOT NULL,
    `plugin_id`   BIGINT(20) NOT NULL,
    `plugin_raw`  JSON        NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_job_uuid` (`job_uuid`),
    INDEX         `idx_job_name` (`job_name`),
    INDEX         `idx_plugin_id_type` (`plugin_id`, `plugin_type`)
);


CREATE TABLE `firefly`.`github_trigger`
(
    `id`                   BIGINT(20) NOT NULL AUTO_INCREMENT,
    `delivery_id`          VARCHAR(64) NULL,
    `pipeline_id`          BIGINT(20) NULL,
    `pipeline_build_id`    BIGINT(20) NULL,
    `github_repository_id` BIGINT(20) NULL,
    `github_repo_url`      VARCHAR(4096) NOT NULL,
    `event_type`           VARCHAR(64) NULL,
    `action`               VARCHAR(64) NULL,
    `source_branch`        VARCHAR(512) NULL,
    `target_branch`        VARCHAR(512) NULL,
    `head_sha`             VARCHAR(64) NULL,
    `legacy_record`        TINYINT(1) NOT NULL DEFAULT 0,
    `created_at`           DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_github_trigger_pipeline_build` (`pipeline_build_id`),
    INDEX `idx_github_trigger_delivery` (`delivery_id`),
    INDEX `idx_github_trigger_pipeline` (`pipeline_id`)
);

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

CREATE TABLE `firefly`.`volcano_trigger`
(
    `id`          BIGINT(20) NOT NULL AUTO_INCREMENT,
    `ak`          VARCHAR(256) NOT NULL,
    `sk`          VARCHAR(256) NOT NULL,
    `pipeline_id` BIGINT(20) NOT NULL,
    PRIMARY KEY (`id`),
    INDEX         `idx_pipeline_id` (`pipeline_id`),
    INDEX         `idx_ak` (`ak`)
);


CREATE TABLE `firefly`.`pipeline_build`
(
    `id`              BIGINT(20) NOT NULL AUTO_INCREMENT,
    `pipeline_id`     BIGINT(20) NOT NULL,
    `pipeline_status` VARCHAR(64) NOT NULL,
    `execution_attempt` INT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    INDEX             `idx_pipeline_id` (`pipeline_id`)
);


CREATE TABLE `firefly`.`stage_build`
(
    `id`                BIGINT(20) NOT NULL AUTO_INCREMENT,
    `pipeline_build_id` BIGINT(20) NOT NULL,
    `stage_id`          BIGINT(20) NOT NULL,
    `stage_status`      VARCHAR(64) NOT NULL,
    `execution_attempt` INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE INDEX       `uidx_pipeline_build_stage` (`pipeline_build_id`, `stage_id`),
    INDEX               `idx_pipeline_build_id` (`pipeline_build_id`),
    INDEX               `idx_stage_id` (`stage_id`)
);


CREATE TABLE `firefly`.`job_build`
(
    `id`             BIGINT(20) NOT NULL AUTO_INCREMENT,
    `stage_build_id` BIGINT(20) NOT NULL,
    `job_id`         BIGINT(20) NOT NULL,
    `job_status`     VARCHAR(64) NOT NULL,
    `execution_attempt` INT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    INDEX            `idx_stage_build_id` (`stage_build_id`),
    INDEX            `idx_job_id` (`job_id`)
);


CREATE TABLE `firefly`.`text_plugin_config`
(
    `id`            BIGINT(20) NOT NULL AUTO_INCREMENT,
    `job_config_id` BIGINT(20) NOT NULL,
    `text`          VARCHAR(64) NOT NULL,
    PRIMARY KEY (`id`),
    INDEX    `idx_job_config_id` (`job_config_id`)
);

CREATE TABLE `firefly`.`text_plugin_build`
(
    `id`                 BIGINT(20) NOT NULL AUTO_INCREMENT,
    `plugin_id`          BIGINT(20) NOT NULL,
    `job_build_id`       BIGINT(20) NOT NULL,
    `text_plugin_status` VARCHAR(64) NOT NULL,
    `execution_attempt`  INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    INDEX                `idx_plugin_id` (`plugin_id`),
    INDEX                `idx_job_build_id` (`job_build_id`)
);



CREATE TABLE `firefly`.`volcano_engine`
(
    `id`          BIGINT(20) NOT NULL AUTO_INCREMENT,
    `pipeline_id` BIGINT(20) NOT NULL,
    `ak`          VARCHAR(256) NOT NULL,
    `sk`          VARCHAR(256) NOT NULL,
    PRIMARY KEY (`id`),
    INDEX         `idx_pipeline_id` (`pipeline_id`),
    INDEX         `idx_ak` (`ak`)
);



CREATE TABLE `firefly`.`job_relation`
(
    `id`              BIGINT(20) NOT NULL AUTO_INCREMENT,
    `pipeline_id`     BIGINT(20) NOT NULL,
    `stage_id`        BIGINT(20) NOT NULL,
    `job_id`          BIGINT(20) NOT NULL,
    `next_job_id`     BIGINT(20) NOT NULL,
    `previous_job_id` BIGINT(20) NOT NULL,
    `is_head_job`     TINYINT(1) NOT NULL,
    PRIMARY KEY (`id`),
    INDEX             `idx_pipeline_id` (`pipeline_id`),
    INDEX             `idx_stage_job_id` (`stage_id`, `job_id`),
    INDEX             `idx_stage_id_head` (`stage_id`, `is_head_job`),
    INDEX             `idx_job_id` (`job_id`),
    INDEX             `idx_next_job_id` (`next_job_id`),
    INDEX             `idx_previous_job_id` (`previous_job_id`)
);


CREATE TABLE `firefly`.`pipeline_message`
(
    `id`                     BIGINT(20)    NOT NULL AUTO_INCREMENT,
    `message_uuid`           VARCHAR(36)   NOT NULL,
    `topic`                  VARCHAR(249)  NOT NULL,
    `kafka_partition`        INT           NOT NULL,
    `kafka_offset`           BIGINT        NOT NULL,
    `message_key`            VARCHAR(512)  NOT NULL DEFAULT '',
    `payload`                LONGTEXT      NOT NULL,
    `received_at`            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `processing_status`      VARCHAR(32)   NOT NULL DEFAULT 'ARCHIVED',
    `processing_attempt`     INT           NOT NULL DEFAULT 0,
    `processor_id`           VARCHAR(128)  NOT NULL DEFAULT '',
    `processing_started_at`  DATETIME(6)   NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    `processing_finished_at` DATETIME(6)   NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    `last_error`             VARCHAR(2048) NOT NULL DEFAULT '',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_pipeline_message_uuid` (`message_uuid`),
    UNIQUE INDEX `uidx_pipeline_message_position` (`topic`, `kafka_partition`, `kafka_offset`),
    INDEX        `idx_pipeline_message_received_at` (`received_at`),
    INDEX        `idx_pipeline_message_processing` (`processing_status`, `received_at`, `id`)
);


CREATE TABLE `firefly`.`stage_message`
(
    `id`                     BIGINT(20)    NOT NULL AUTO_INCREMENT,
    `message_uuid`           VARCHAR(36)   NOT NULL,
    `topic`                  VARCHAR(249)  NOT NULL,
    `kafka_partition`        INT           NOT NULL,
    `kafka_offset`           BIGINT        NOT NULL,
    `message_key`            VARCHAR(512)  NOT NULL DEFAULT '',
    `payload`                LONGTEXT      NOT NULL,
    `received_at`            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `processing_status`      VARCHAR(32)   NOT NULL DEFAULT 'ARCHIVED',
    `processing_attempt`     INT           NOT NULL DEFAULT 0,
    `processor_id`           VARCHAR(128)  NOT NULL DEFAULT '',
    `processing_started_at`  DATETIME(6)   NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    `processing_finished_at` DATETIME(6)   NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    `last_error`             VARCHAR(2048) NOT NULL DEFAULT '',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_stage_message_uuid` (`message_uuid`),
    UNIQUE INDEX `uidx_stage_message_position` (`topic`, `kafka_partition`, `kafka_offset`),
    INDEX        `idx_stage_message_received_at` (`received_at`),
    INDEX        `idx_stage_message_processing` (`processing_status`, `received_at`, `id`)
);


CREATE TABLE `firefly`.`job_message`
(
    `id`                     BIGINT(20)    NOT NULL AUTO_INCREMENT,
    `message_uuid`           VARCHAR(36)   NOT NULL,
    `topic`                  VARCHAR(249)  NOT NULL,
    `kafka_partition`        INT           NOT NULL,
    `kafka_offset`           BIGINT        NOT NULL,
    `message_key`            VARCHAR(512)  NOT NULL DEFAULT '',
    `payload`                LONGTEXT      NOT NULL,
    `received_at`            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `processing_status`      VARCHAR(32)   NOT NULL DEFAULT 'ARCHIVED',
    `processing_attempt`     INT           NOT NULL DEFAULT 0,
    `processor_id`           VARCHAR(128)  NOT NULL DEFAULT '',
    `processing_started_at`  DATETIME(6)   NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    `processing_finished_at` DATETIME(6)   NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    `last_error`             VARCHAR(2048) NOT NULL DEFAULT '',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_job_message_uuid` (`message_uuid`),
    UNIQUE INDEX `uidx_job_message_position` (`topic`, `kafka_partition`, `kafka_offset`),
    INDEX        `idx_job_message_received_at` (`received_at`),
    INDEX        `idx_job_message_processing` (`processing_status`, `received_at`, `id`)
);


CREATE TABLE `firefly`.`plugin_message`
(
    `id`                     BIGINT(20)    NOT NULL AUTO_INCREMENT,
    `message_uuid`           VARCHAR(36)   NOT NULL,
    `topic`                  VARCHAR(249)  NOT NULL,
    `kafka_partition`        INT           NOT NULL,
    `kafka_offset`           BIGINT        NOT NULL,
    `message_key`            VARCHAR(512)  NOT NULL DEFAULT '',
    `payload`                LONGTEXT      NOT NULL,
    `received_at`            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `processing_status`      VARCHAR(32)   NOT NULL DEFAULT 'ARCHIVED',
    `processing_attempt`     INT           NOT NULL DEFAULT 0,
    `processor_id`           VARCHAR(128)  NOT NULL DEFAULT '',
    `processing_started_at`  DATETIME(6)   NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    `processing_finished_at` DATETIME(6)   NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    `last_error`             VARCHAR(2048) NOT NULL DEFAULT '',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_plugin_message_uuid` (`message_uuid`),
    UNIQUE INDEX `uidx_plugin_message_position` (`topic`, `kafka_partition`, `kafka_offset`),
    INDEX        `idx_plugin_message_received_at` (`received_at`),
    INDEX        `idx_plugin_message_processing` (`processing_status`, `received_at`, `id`)
);


-- Outbox persists outbound Kafka events in the same MySQL transaction as the
-- corresponding business state change. It closes the database-commit/Kafka-send
-- gap without polling: one publish attempt runs after commit, and PENDING,
-- PUBLISHING, or FAILED events are recovered manually.
CREATE TABLE `firefly`.`outbox_event`
(
    `id`                     BIGINT        NOT NULL AUTO_INCREMENT,
    `message_uuid`           VARCHAR(36)   NOT NULL,
    `topic`                  VARCHAR(249)  NOT NULL,
    `message_key`            VARCHAR(128)  NOT NULL DEFAULT '',
    `message_type`           VARCHAR(128)  NOT NULL DEFAULT '',
    `payload`                LONGTEXT      NOT NULL,
    `publish_status`         VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    `publish_attempt`        INT           NOT NULL DEFAULT 0,
    `publisher_id`           VARCHAR(128)  NOT NULL DEFAULT '',
    `publishing_started_at`  DATETIME(6)   NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    `publishing_finished_at` DATETIME(6)   NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    `last_error`             VARCHAR(2048) NOT NULL DEFAULT '',
    `created_at`             DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_outbox_message_uuid` (`message_uuid`),
    INDEX `idx_outbox_publish_status` (`publish_status`, `created_at`, `id`)
);
