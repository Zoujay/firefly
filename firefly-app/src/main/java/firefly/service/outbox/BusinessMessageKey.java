package firefly.service.outbox;

import firefly.bean.dto.message.KafkaBusinessMessage;
import firefly.bean.dto.message.TriggerJobMessage;
import firefly.bean.dto.message.TriggerPipelineMessage;
import firefly.bean.dto.message.TriggerPluginMessage;
import firefly.bean.dto.message.TriggerStageMessage;
import firefly.github.message.GitHubWebhookMessage;

/**
 * Builds a stable Kafka key for the business execution represented by a message. The key
 * deliberately excludes status and message UUID: events for the same build attempt stay in one
 * partition and retain their order, while unrelated builds can still be consumed in parallel.
 */
public final class BusinessMessageKey {

    private BusinessMessageKey() {}

    public static String from(KafkaBusinessMessage message) {
        if (message instanceof TriggerPipelineMessage pipeline) {
            return key("pipeline", pipeline.getPipelineBuildID(), pipeline.getExecutionAttempt());
        }
        if (message instanceof TriggerStageMessage stage) {
            return key("stage", stage.getStageBuildID(), stage.getExecutionAttempt());
        }
        if (message instanceof TriggerJobMessage job) {
            return key("job", job.getJobBuildID(), job.getExecutionAttempt());
        }
        if (message instanceof TriggerPluginMessage plugin) {
            return key("plugin", plugin.getPluginBuildID(), plugin.getExecutionAttempt());
        }
        if (message instanceof GitHubWebhookMessage github) {
            return "github-delivery:" + github.getDeliveryId();
        }
        throw new IllegalArgumentException(
                "Unsupported Kafka business message: " + message.getClass().getName());
    }

    private static String key(String category, Long buildID, Integer executionAttempt) {
        return category + ":" + buildID + ":" + executionAttempt;
    }
}
