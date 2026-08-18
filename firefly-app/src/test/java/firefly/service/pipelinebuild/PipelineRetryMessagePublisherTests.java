package firefly.service.pipelinebuild;

import static firefly.constant.KafkaConfiguration.STAGE_TOPIC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import firefly.bean.dto.message.TriggerStageMessage;
import firefly.constant.BuildStatus;
import firefly.service.messagecenter.BusinessMessageUUID;
import firefly.service.outbox.OutboxService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PipelineRetryMessagePublisherTests {

    @Mock private OutboxService outboxService;

    @InjectMocks private PipelineRetryMessagePublisher publisher;

    @Test
    void publishesTheFirstRetryStageWithTheNewExecutionAttempt() {
        publisher.publish(new PipelineRetryPreparedEvent(11L, 2));

        ArgumentCaptor<TriggerStageMessage> messageCaptor =
                ArgumentCaptor.forClass(TriggerStageMessage.class);
        String expectedUUID = BusinessMessageUUID.stage(11L, 2, BuildStatus.RUNNING);
        verify(outboxService).enqueue(eq(STAGE_TOPIC), messageCaptor.capture());
        assertEquals(11L, messageCaptor.getValue().getStageBuildID());
        assertEquals(2, messageCaptor.getValue().getExecutionAttempt());
        assertEquals(BuildStatus.RUNNING, messageCaptor.getValue().getBuildStatus());
        assertEquals(expectedUUID, messageCaptor.getValue().getMessageUUID());
    }
}
