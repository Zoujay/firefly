package firefly.service.trigger;

import firefly.bean.dto.message.BaseMessage;
import firefly.bean.dto.message.TriggerPipelineMessage;
import firefly.constant.BuildStatus;
import firefly.constant.KafkaConfiguration;
import firefly.model.trigger.BaseTriggerEntity;
import firefly.service.messagecenter.BusinessMessageUUID;
import firefly.service.outbox.OutboxService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

public abstract class AbstractTrigger<
        T extends BaseTriggerEntity,
        M extends BaseMessage>
        implements ITrigger<M> {

    @Autowired
    private OutboxService outboxService;

    protected abstract T saveRealTrigger(M message);

    @Override
    @Transactional
    public void dispatch(BaseMessage baseMessage) {
        M message = validateAndCast(baseMessage);
        validateRequiredFields(message);

        T triggerEntity = saveRealTrigger(message);
        if (triggerEntity == null
                || triggerEntity.getId() == null
                || triggerEntity.getId() <= 0) {
            throw new IllegalStateException(
                    "Trigger record ID was not generated: "
                            + getTriggerOrigin()
            );
        }

        message.setTriggerID(triggerEntity.getId());

        TriggerPipelineMessage pipelineMessage =
                new TriggerPipelineMessage()
                        .setPipelineID(message.getPipelineID())
                        .setPipelineBuildID(message.getPipelineBuildID())
                        .setExecutionAttempt(message.getExecutionAttempt())
                        .setBuildStatus(BuildStatus.RUNNING)
                        .setMessageUUID(BusinessMessageUUID.pipeline(
                                message.getPipelineBuildID(),
                                message.getExecutionAttempt(),
                                BuildStatus.RUNNING
                        ));

        /*
         * The trigger record and Outbox event share this transaction. A
         * failure rolls both back, while a commit lets the Outbox listener
         * publish the Pipeline message after the database state is durable.
         */
        outboxService.enqueue(
                KafkaConfiguration.PIPELINE_TOPIC,
                pipelineMessage
        );
    }

    private M validateAndCast(BaseMessage message) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "Trigger message must not be null"
            );
        }
        if (message.getTriggerOrigin() == null) {
            throw new IllegalArgumentException(
                    "Trigger origin must not be null"
            );
        }
        if (message.getTriggerOrigin() != getTriggerOrigin()) {
            throw new IllegalArgumentException(
                    "Trigger origin mismatch: expected "
                            + getTriggerOrigin()
                            + ", actual "
                            + message.getTriggerOrigin()
            );
        }

        Class<M> messageType = getMessageType();
        if (messageType == null) {
            throw new IllegalStateException(
                    "Trigger message type is not configured: "
                            + getTriggerOrigin()
            );
        }
        if (!messageType.isInstance(message)) {
            throw new IllegalArgumentException(
                    "Trigger message type mismatch: expected "
                            + messageType.getName()
                            + ", actual "
                            + message.getClass().getName()
            );
        }
        return messageType.cast(message);
    }

    private void validateRequiredFields(M message) {
        if (message.getPipelineID() == null
                || message.getPipelineID() <= 0) {
            throw new IllegalArgumentException(
                    "pipelineID must be positive"
            );
        }
        if (message.getPipelineBuildID() == null
                || message.getPipelineBuildID() <= 0) {
            throw new IllegalArgumentException(
                    "pipelineBuildID must be positive"
            );
        }
        if (message.getExecutionAttempt() == null
                || message.getExecutionAttempt() < 0) {
            throw new IllegalArgumentException(
                    "executionAttempt must not be negative"
            );
        }
    }
}
