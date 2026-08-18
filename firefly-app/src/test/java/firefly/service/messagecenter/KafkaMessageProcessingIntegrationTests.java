package firefly.service.messagecenter;

import static firefly.constant.KafkaConfiguration.PIPELINE_TOPIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import firefly.bean.dto.message.TriggerPipelineMessage;
import firefly.bean.vo.response.KafkaMessageProcessingResponse;
import firefly.constant.BuildStatus;
import firefly.constant.MessageCategory;
import firefly.constant.MessageProcessingStatus;
import firefly.support.FireflyIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@FireflyIntegrationTest
class KafkaMessageProcessingIntegrationTests {

  @Autowired private KafkaMessageStore kafkaMessageStore;

  @Autowired private KafkaMessageProcessingCoordinator processingCoordinator;

  @Autowired private KafkaMessageStateService stateService;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private MessageCenter messageCenter;

  @Test
  void processesAnInboxUUIDOnlyOnceAfterSuccess() throws Exception {
    TriggerPipelineMessage message = archiveMessage(101L);

    assertTrue(processingCoordinator.process(MessageCategory.PIPELINE, message.getMessageUUID()));
    assertFalse(processingCoordinator.process(MessageCategory.PIPELINE, message.getMessageUUID()));

    KafkaMessageProcessingResponse result =
        stateService.getResponse(MessageCategory.PIPELINE, message.getMessageUUID());
    assertEquals(MessageProcessingStatus.SUCCESS, result.getProcessingStatus());
    assertEquals(1, result.getProcessingAttempt());
    verify(messageCenter, times(1)).onPipelineMessage(message);
  }

  @Test
  void movesFailureBackThroughProcessingOnlyOnManualRetry() throws Exception {
    TriggerPipelineMessage message = archiveMessage(102L);
    when(messageCenter.onPipelineMessage(any()))
        .thenThrow(new IllegalStateException("business failed"))
        .thenReturn(true);

    assertFalse(processingCoordinator.process(MessageCategory.PIPELINE, message.getMessageUUID()));
    KafkaMessageProcessingResponse failed =
        stateService.getResponse(MessageCategory.PIPELINE, message.getMessageUUID());
    assertEquals(MessageProcessingStatus.FAILURE, failed.getProcessingStatus());
    assertEquals(1, failed.getProcessingAttempt());
    assertEquals("business failed", failed.getLastError());

    assertTrue(processingCoordinator.process(MessageCategory.PIPELINE, message.getMessageUUID()));
    KafkaMessageProcessingResponse succeeded =
        stateService.getResponse(MessageCategory.PIPELINE, message.getMessageUUID());
    assertEquals(MessageProcessingStatus.SUCCESS, succeeded.getProcessingStatus());
    assertEquals(2, succeeded.getProcessingAttempt());
  }

  private TriggerPipelineMessage archiveMessage(Long pipelineBuildID) throws Exception {
    TriggerPipelineMessage message =
        new TriggerPipelineMessage()
            .setMessageUUID(UUID.randomUUID().toString())
            .setPipelineID(1L)
            .setPipelineBuildID(pipelineBuildID)
            .setBuildStatus(BuildStatus.RUNNING)
            .setExecutionAttempt(0);
    String payload = objectMapper.writeValueAsString(message);
    kafkaMessageStore.savePipelineMessages(
        List.of(
            new ConsumerRecord<>(
                PIPELINE_TOPIC, 0, pipelineBuildID, message.getMessageUUID(), payload)));
    return message;
  }
}
