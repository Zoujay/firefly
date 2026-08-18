package firefly.service.messagecenter;

import static firefly.constant.KafkaConfiguration.JOB_TOPIC;
import static firefly.constant.KafkaConfiguration.PIPELINE_TOPIC;
import static firefly.constant.KafkaConfiguration.PLUGIN_TOPIC;
import static firefly.constant.KafkaConfiguration.STAGE_TOPIC;

import firefly.constant.MessageCategory;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MessageListener {

  private static final int MAX_CONCURRENT_MESSAGES = 24;
  private static final int SHUTDOWN_TIMEOUT_SECONDS = 30;

  private final ExecutorService messageExecutor =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("kafka-message-", 0).factory());

  private final Semaphore processingPermits = new Semaphore(MAX_CONCURRENT_MESSAGES, true);

  @Autowired private KafkaMessageStore kafkaMessageStore;

  @Autowired private KafkaMessageProcessingCoordinator processingCoordinator;

  @KafkaListener(topics = PIPELINE_TOPIC)
  public void onPipelineMessage(List<ConsumerRecord<String, String>> messages, Acknowledgment ack) {
    KafkaMessageSaveResult saveResult = kafkaMessageStore.savePipelineMessages(messages);
    logPersistenceResult(MessageCategory.PIPELINE, messages.size(), saveResult);
    ack.acknowledge();
    processMessages(saveResult.newMessages(), MessageCategory.PIPELINE);
  }

  @KafkaListener(topics = STAGE_TOPIC)
  public void onStageMessage(List<ConsumerRecord<String, String>> messages, Acknowledgment ack) {
    KafkaMessageSaveResult saveResult = kafkaMessageStore.saveStageMessages(messages);
    logPersistenceResult(MessageCategory.STAGE, messages.size(), saveResult);
    ack.acknowledge();
    processMessages(saveResult.newMessages(), MessageCategory.STAGE);
  }

  @KafkaListener(topics = JOB_TOPIC)
  public void onJobMessage(List<ConsumerRecord<String, String>> messages, Acknowledgment ack) {
    KafkaMessageSaveResult saveResult = kafkaMessageStore.saveJobMessages(messages);
    logPersistenceResult(MessageCategory.JOB, messages.size(), saveResult);
    ack.acknowledge();
    processMessages(saveResult.newMessages(), MessageCategory.JOB);
  }

  @KafkaListener(topics = PLUGIN_TOPIC)
  public void onPluginMessage(List<ConsumerRecord<String, String>> messages, Acknowledgment ack) {
    KafkaMessageSaveResult saveResult = kafkaMessageStore.savePluginMessages(messages);
    logPersistenceResult(MessageCategory.PLUGIN, messages.size(), saveResult);
    ack.acknowledge();
    processMessages(saveResult.newMessages(), MessageCategory.PLUGIN);
  }

  private void processMessages(
      List<ConsumerRecord<String, String>> messages, MessageCategory messageCategory) {
    for (ConsumerRecord<String, String> record : messages) {
      try {
        processingPermits.acquire();
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        log.warn(
            "Interrupted while scheduling {} messages; unscheduled messages remain ARCHIVED and"
                + " require manual recovery",
            messageCategory);
        return;
      }

      try {
        messageExecutor.execute(
            () -> {
              try {
                processMessage(record, messageCategory);
              } finally {
                processingPermits.release();
              }
            });
      } catch (RejectedExecutionException exception) {
        processingPermits.release();
        log.error(
            "Failed to schedule archived {} message at {}-{}@{}; manual recovery is required",
            messageCategory,
            record.topic(),
            record.partition(),
            record.offset(),
            exception);
      }
    }
  }

  private void processMessage(
      ConsumerRecord<String, String> record, MessageCategory messageCategory) {
    try {
      String messageUUID = kafkaMessageStore.extractMessageUUID(record);
      /*
       * Inbox 已经在 ACK 前提交。ACK 之后的业务处理只操作 Inbox 中的消息：
       * 成功转为 SUCCESS，异常转为 FAILURE，Kafka 不会因为业务异常而重复投递。
       * FAILURE/宕机遗留的 PROCESSING 由管理员通过恢复接口显式处理，不扫描数据库。
       */
      processingCoordinator.process(messageCategory, messageUUID);
    } catch (Exception exception) {
      log.error(
          "Failed to process archived {} message at {}-{}@{}; manual recovery is required",
          messageCategory,
          record.topic(),
          record.partition(),
          record.offset(),
          exception);
    }
  }

  @PreDestroy
  public void shutdownMessageExecutor() {
    messageExecutor.shutdown();
    try {
      if (!messageExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        log.warn(
            "Kafka message executor did not terminate within {} seconds; unfinished Inbox messages"
                + " require manual recovery",
            SHUTDOWN_TIMEOUT_SECONDS);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted while shutting down Kafka message executor");
    }
  }

  private void logPersistenceResult(
      MessageCategory messageCategory, int receivedCount, KafkaMessageSaveResult saveResult) {
    log.info(
        "Archived {} message batch: received={}, new={}, duplicate={}",
        messageCategory,
        receivedCount,
        saveResult.newMessages().size(),
        saveResult.duplicateCount());
  }
}
