package firefly.service.pipelinebuild;

import firefly.bean.dto.message.TriggerStageMessage;
import firefly.constant.BuildStatus;
import firefly.constant.KafkaConfiguration;
import firefly.service.messagecenter.BusinessMessageUUID;
import firefly.service.outbox.OutboxService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class PipelineRetryMessagePublisher {

    @Autowired
    private OutboxService outboxService;

    /*
     * BEFORE_COMMIT is required here: OutboxService uses MANDATORY and must
     * join retryPipeline's existing transaction. AFTER_COMMIT would be too
     * late—the transaction would already be closed and the Outbox insert
     * could no longer be atomic with the retry state changes.
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void publish(PipelineRetryPreparedEvent event) {
        TriggerStageMessage message = new TriggerStageMessage();
        message.setStageBuildID(event.stageBuildID())
                .setBuildStatus(BuildStatus.RUNNING)
                .setExecutionAttempt(event.executionAttempt())
                .setMessageUUID(BusinessMessageUUID.stage(
                        event.stageBuildID(),
                        event.executionAttempt(),
                        BuildStatus.RUNNING
                ));
        outboxService.enqueue(KafkaConfiguration.STAGE_TOPIC, message);
    }
}
