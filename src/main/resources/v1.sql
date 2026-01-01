CREATE TABLE `firefly`.`pipeline_config`
(
    `id`            BIGINT(20) NOT NULL AUTO_INCREMENT,
    `pipeline_uuid` VARCHAR(64) NOT NULL DEFAULT "",
    `pipeline_name` VARCHAR(64) NOT NULL DEFAULT "",
    `trigger_mode`  VARCHAR(64) NOT NULL,
    `trigger_match` VARCHAR(64) NOT NULL,
    `trigger_origin` VARCHAR(64) NOT NULL,
    `origin_id` BIGINT(20) NOT NULL DEFAULT -1,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_pipeline_uuid` (`pipeline_uuid`),
    INDEX           `idx_pipeline_name` (`pipeline_name`),
    INDEX           `idx_trigger_origin` (`origin_id`, `trigger_origin`)
);


CREATE TABLE `firefly`.`volcano_config`
(
    `id`            BIGINT(20) NOT NULL AUTO_INCREMENT,
    `pipeline_id` BIGINT(20) NOT NULL,
    `ak` VARCHAR(1024) NOT NULL,
    `sk` VARCHAR(1024) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_pipeline_id` (`pipeline_id`)
);


CREATE TABLE `firefly`.`stage_config`
(
    `id`              BIGINT(20) NOT NULL AUTO_INCREMENT,
    `pipeline_id`     BIGINT(20) NOT NULL,
    `stage_uuid`      VARCHAR(64) NOT NULL,
    `stage_name`      VARCHAR(64) NOT NULL,
    `is_job_parallel` TINYINT(1) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_stage_uuid` (`stage_uuid`),
    INDEX             `idx_stage_name` (`stage_name`),
    INDEX             `idx_pipeline_id` (`pipeline_id`)
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
    `id`              BIGINT(20) NOT NULL AUTO_INCREMENT,
    `github_repo_url` VARCHAR(4096) NOT NULL,
    PRIMARY KEY (`id`)
);

CREATE TABLE `firefly`.`volcano_trigger`
(
    `id`            BIGINT(20) NOT NULL AUTO_INCREMENT,
    `ak` VARCHAR(256) NOT NULL,
    `sk` VARCHAR(256) NOT NULL,
    `pipeline_id` BIGINT(20) NOT NULL,
    PRIMARY KEY (`id`),
    INDEX                `idx_pipeline_id` (`pipeline_id`),
    INDEX                `idx_ak` (`ak`)
);


CREATE TABLE `firefly`.`pipeline_build`
(
    `id`              BIGINT(20) NOT NULL AUTO_INCREMENT,
    `pipeline_id`     BIGINT(20) NOT NULL,
    `pipeline_status` VARCHAR(64) NOT NULL,
    PRIMARY KEY (`id`),
    INDEX             `idx_pipeline_id` (`pipeline_id`)
);


CREATE TABLE `firefly`.`stage_build`
(
    `id`                BIGINT(20) NOT NULL AUTO_INCREMENT,
    `pipeline_build_id` BIGINT(20) NOT NULL,
    `stage_id`          BIGINT(20) NOT NULL,
    `stage_status`      VARCHAR(64) NOT NULL,
    PRIMARY KEY (`id`),
    INDEX               `idx_pipeline_build_id` (`pipeline_build_id`),
    INDEX               `idx_stage_id` (`stage_id`)
);


CREATE TABLE `firefly`.`job_build`
(
    `id`             BIGINT(20) NOT NULL AUTO_INCREMENT,
    `stage_build_id` BIGINT(20) NOT NULL,
    `job_id`         BIGINT(20) NOT NULL,
    `job_status`     VARCHAR(64) NOT NULL,
    PRIMARY KEY (`id`),
    INDEX            `idx_stage_build_id` (`stage_build_id`),
    INDEX            `idx_job_id` (`job_id`)
);


CREATE TABLE `firefly`.`text_plugin_config`
(
    `id`     BIGINT(20) NOT NULL AUTO_INCREMENT,
    `job_id` BIGINT(20) NOT NULL,
    `text`   VARCHAR(64) NOT NULL,
    PRIMARY KEY (`id`),
    INDEX    `idx_job_id` (`job_id`)
);

CREATE TABLE `firefly`.`text_plugin_build`
(
    `id`                 BIGINT(20) NOT NULL AUTO_INCREMENT,
    `plugin_id`          BIGINT(20) NOT NULL,
    `job_build_id`       BIGINT(20) NOT NULL,
    `text_plugin_status` VARCHAR(64) NOT NULL,
    PRIMARY KEY (`id`),
    INDEX                `idx_plugin_id` (`plugin_id`),
    INDEX                `idx_job_build_id` (`job_build_id`)
);



CREATE TABLE `firefly`.`volcano_engine`
(
    `id`                 BIGINT(20) NOT NULL AUTO_INCREMENT,
    `pipeline_id`          BIGINT(20) NOT NULL,
    `ak`       VARCHAR(256) NOT NULL,
    `sk` VARCHAR(256) NOT NULL,
    PRIMARY KEY (`id`),
    INDEX                `idx_pipeline_id` (`pipeline_id`),
    INDEX                `idx_ak` (`ak`)
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
