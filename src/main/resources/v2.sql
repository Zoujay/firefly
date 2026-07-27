ALTER TABLE `firefly`.`stage_config`
    ADD COLUMN `stage_order` INT NULL AFTER `pipeline_id`;

UPDATE `firefly`.`stage_config` AS stage
    JOIN (
        SELECT
            `id`,
            ROW_NUMBER() OVER (
                PARTITION BY `pipeline_id`
                ORDER BY `id`
            ) - 1 AS `stage_order`
        FROM `firefly`.`stage_config`
    ) AS ordered_stage ON ordered_stage.`id` = stage.`id`
SET stage.`stage_order` = ordered_stage.`stage_order`;

ALTER TABLE `firefly`.`stage_config`
    MODIFY COLUMN `stage_order` INT NOT NULL,
    ADD UNIQUE INDEX `uidx_pipeline_stage_order` (`pipeline_id`, `stage_order`);

ALTER TABLE `firefly`.`stage_build`
    ADD UNIQUE INDEX `uidx_pipeline_build_stage` (`pipeline_build_id`, `stage_id`);
