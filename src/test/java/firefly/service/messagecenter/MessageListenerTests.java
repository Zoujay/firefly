package firefly.service.messagecenter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageListenerTests {

    @Mock
    private MessageCenter messageCenter;

    @Mock
    private Acknowledgment acknowledgment;

    @InjectMocks
    private MessageListener messageListener;

    @Test
    void acknowledgesPipelineBatchAfterEveryMessageSucceeds() {
        List<String> messages = List.of(
                """
                {"messageUUID":"pipeline-1","pipelineID":1,"pipelineBuildID":11,"buildStatus":"RUNNING"}
                """,
                """
                {"messageUUID":"pipeline-2","pipelineID":2,"pipelineBuildID":22,"buildStatus":"RUNNING"}
                """
        );

        messageListener.onPipelineMessage(messages, acknowledgment);

        InOrder inOrder = inOrder(messageCenter, acknowledgment);
        inOrder.verify(messageCenter, times(2)).onPipelineMessage(any());
        inOrder.verify(acknowledgment).acknowledge();
    }

    @Test
    void doesNotAcknowledgeBatchWhenProcessingFails() {
        doThrow(new IllegalStateException("processing failed"))
                .when(messageCenter).onPipelineMessage(any());

        assertThrows(
                IllegalStateException.class,
                () -> messageListener.onPipelineMessage(
                        List.of("""
                                {"messageUUID":"pipeline-1","pipelineID":1,"pipelineBuildID":11,"buildStatus":"RUNNING"}
                                """),
                        acknowledgment
                )
        );

        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void acknowledgesStageBatchAfterProcessing() {
        messageListener.onStageMessage(
                List.of("""
                        {"messageUUID":"stage-1","stageBuildID":11,"buildStatus":"RUNNING"}
                        """),
                acknowledgment
        );

        verify(messageCenter).onStageMessage(any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void acknowledgesJobBatchAfterProcessing() {
        messageListener.onJobMessage(
                List.of("""
                        {"messageUUID":"job-1","jobBuildID":11,"buildStatus":"RUNNING"}
                        """),
                acknowledgment
        );

        verify(messageCenter).onJobMessage(any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void acknowledgesPluginBatchAfterProcessing() {
        messageListener.onPluginMessage(
                List.of("""
                        {"messageUUID":"plugin-1","pluginType":"TEXT","pluginBuildID":11,"status":"SUCCESS"}
                        """),
                acknowledgment
        );

        verify(messageCenter).onPluginMessage(any());
        verify(acknowledgment).acknowledge();
    }
}
