package firefly.dao.message;

import static firefly.constant.KafkaConfiguration.JOB_TOPIC;
import static firefly.constant.KafkaConfiguration.PIPELINE_TOPIC;
import static firefly.constant.KafkaConfiguration.PLUGIN_TOPIC;
import static firefly.constant.KafkaConfiguration.STAGE_TOPIC;
import static firefly.constant.PersistenceDefaults.UNSET_TIME;
import static org.junit.jupiter.api.Assertions.assertEquals;

import firefly.constant.MessageProcessingStatus;
import firefly.model.message.JobMessage;
import firefly.model.message.KafkaMessage;
import firefly.model.message.PipelineMessage;
import firefly.model.message.PluginMessage;
import firefly.model.message.StageMessage;
import firefly.support.FireflyIntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@FireflyIntegrationTest
@Transactional
class KafkaMessageExplicitQueryIntegrationTests {

  private static final List<MessageProcessingStatus> CLAIMABLE_STATUSES =
      List.of(MessageProcessingStatus.ARCHIVED, MessageProcessingStatus.FAILURE);

  @Autowired private IPipelineMessageDao pipelineMessageDao;

  @Autowired private IStageMessageDao stageMessageDao;

  @Autowired private IJobMessageDao jobMessageDao;

  @Autowired private IPluginMessageDao pluginMessageDao;

  private long nextOffset = 10_000_000L;

  @Test
  void explicitQueriesUpdateEachConcreteInboxEntity() {
    verifyStateTransitions(pipelineMessageDao, pipelineMessage(), pipelineMessage());
    verifyStateTransitions(stageMessageDao, stageMessage(), stageMessage());
    verifyStateTransitions(jobMessageDao, jobMessage(), jobMessage());
    verifyStateTransitions(pluginMessageDao, pluginMessage(), pluginMessage());
  }

  private <T extends KafkaMessage> void verifyStateTransitions(
      IKafkaMessageDao<T> dao, T successfulMessage, T retriedMessage) {
    dao.save(successfulMessage);
    assertEquals(1, claim(dao, successfulMessage.getMessageUUID(), "success-worker"));
    assertEquals(
        1,
        dao.markProcessingSuccess(
            successfulMessage.getMessageUUID(),
            MessageProcessingStatus.PROCESSING,
            MessageProcessingStatus.SUCCESS,
            "success-worker",
            LocalDateTime.now(),
            StringUtils.EMPTY));
    KafkaMessage success = findRequired(dao, successfulMessage.getMessageUUID());
    assertEquals(MessageProcessingStatus.SUCCESS, success.getProcessingStatus());
    assertEquals(1, success.getProcessingAttempt());
    assertEquals("success-worker", success.getProcessorID());

    dao.save(retriedMessage);
    assertEquals(1, claim(dao, retriedMessage.getMessageUUID(), "failure-worker"));
    assertEquals(
        0,
        dao.markProcessingSuccess(
            retriedMessage.getMessageUUID(),
            MessageProcessingStatus.PROCESSING,
            MessageProcessingStatus.SUCCESS,
            "wrong-worker",
            LocalDateTime.now(),
            StringUtils.EMPTY));
    assertEquals(
        1,
        dao.markProcessingFailure(
            retriedMessage.getMessageUUID(),
            MessageProcessingStatus.PROCESSING,
            MessageProcessingStatus.FAILURE,
            "failure-worker",
            LocalDateTime.now(),
            "business failed"));
    KafkaMessage failure = findRequired(dao, retriedMessage.getMessageUUID());
    assertEquals(MessageProcessingStatus.FAILURE, failure.getProcessingStatus());
    assertEquals(1, failure.getProcessingAttempt());
    assertEquals("business failed", failure.getLastError());

    assertEquals(1, claim(dao, retriedMessage.getMessageUUID(), "retry-worker"));
    KafkaMessage retry = findRequired(dao, retriedMessage.getMessageUUID());
    assertEquals(MessageProcessingStatus.PROCESSING, retry.getProcessingStatus());
    assertEquals(2, retry.getProcessingAttempt());
    assertEquals("retry-worker", retry.getProcessorID());
    assertEquals(StringUtils.EMPTY, retry.getLastError());
  }

  private <T extends KafkaMessage> int claim(
      IKafkaMessageDao<T> dao, String messageUUID, String processorID) {
    return dao.claimForProcessing(
        messageUUID,
        CLAIMABLE_STATUSES,
        MessageProcessingStatus.PROCESSING,
        processorID,
        LocalDateTime.now(),
        UNSET_TIME,
        StringUtils.EMPTY);
  }

  private <T extends KafkaMessage> KafkaMessage findRequired(
      IKafkaMessageDao<T> dao, String messageUUID) {
    return dao.findByMessageUUID(messageUUID).orElseThrow();
  }

  private PipelineMessage pipelineMessage() {
    return new PipelineMessage(
        UUID.randomUUID().toString(),
        PIPELINE_TOPIC,
        0,
        nextOffset++,
        UUID.randomUUID().toString(),
        "{}");
  }

  private StageMessage stageMessage() {
    return new StageMessage(
        UUID.randomUUID().toString(),
        STAGE_TOPIC,
        0,
        nextOffset++,
        UUID.randomUUID().toString(),
        "{}");
  }

  private JobMessage jobMessage() {
    return new JobMessage(
        UUID.randomUUID().toString(),
        JOB_TOPIC,
        0,
        nextOffset++,
        UUID.randomUUID().toString(),
        "{}");
  }

  private PluginMessage pluginMessage() {
    return new PluginMessage(
        UUID.randomUUID().toString(),
        PLUGIN_TOPIC,
        0,
        nextOffset++,
        UUID.randomUUID().toString(),
        "{}");
  }
}
