package firefly.service.pipelinebuild;

import firefly.bean.dto.message.TriggerStageMessage;
import firefly.constant.BuildStatus;
import firefly.constant.KafkaConfiguration;
import firefly.service.messagecenter.BusinessMessageUUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class PipelineRetryMessagePublisher {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
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
        kafkaTemplate.send(
                KafkaConfiguration.STAGE_TOPIC,
                message.getMessageUUID(),
                message
        );
    }
}
