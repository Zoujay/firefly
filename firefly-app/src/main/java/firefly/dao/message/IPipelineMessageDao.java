package firefly.dao.message;

import firefly.constant.MessageProcessingStatus;
import firefly.model.message.PipelineMessage;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface IPipelineMessageDao extends IKafkaMessageDao<PipelineMessage> {

  @Modifying
  @Query(
      value =
          """
          INSERT IGNORE INTO pipeline_message
              (message_uuid, topic, kafka_partition, kafka_offset, message_key, payload)
          VALUES
              (:messageUUID, :topic, :kafkaPartition, :kafkaOffset, :messageKey, :payload)
          """,
      nativeQuery = true)
  int insertIfAbsent(
      @Param("messageUUID") String messageUUID,
      @Param("topic") String topic,
      @Param("kafkaPartition") Integer kafkaPartition,
      @Param("kafkaOffset") Long kafkaOffset,
      @Param("messageKey") String messageKey,
      @Param("payload") String payload);

  @Override
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update PipelineMessage m
      set m.processingStatus = :targetStatus,
          m.processingAttempt = m.processingAttempt + 1,
          m.processorID = :processorID,
          m.processingStartedAt = :startedAt,
          m.processingFinishedAt = :unfinishedAt,
          m.lastError = :emptyValue
      where m.messageUUID = :messageUUID
        and m.processingStatus in :expectedStatuses
      """)
  int claimForProcessing(
      @Param("messageUUID") String messageUUID,
      @Param("expectedStatuses") List<MessageProcessingStatus> expectedStatuses,
      @Param("targetStatus") MessageProcessingStatus targetStatus,
      @Param("processorID") String processorID,
      @Param("startedAt") LocalDateTime startedAt,
      @Param("unfinishedAt") LocalDateTime unfinishedAt,
      @Param("emptyValue") String emptyValue);

  @Override
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update PipelineMessage m
      set m.processingStatus = :targetStatus,
          m.processingFinishedAt = :finishedAt,
          m.lastError = :emptyValue
      where m.messageUUID = :messageUUID
        and m.processingStatus = :expectedStatus
        and m.processorID = :processorID
      """)
  int markProcessingSuccess(
      @Param("messageUUID") String messageUUID,
      @Param("expectedStatus") MessageProcessingStatus expectedStatus,
      @Param("targetStatus") MessageProcessingStatus targetStatus,
      @Param("processorID") String processorID,
      @Param("finishedAt") LocalDateTime finishedAt,
      @Param("emptyValue") String emptyValue);

  @Override
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update PipelineMessage m
      set m.processingStatus = :targetStatus,
          m.processingFinishedAt = :finishedAt,
          m.lastError = :lastError
      where m.messageUUID = :messageUUID
        and m.processingStatus = :expectedStatus
        and m.processorID = :processorID
      """)
  int markProcessingFailure(
      @Param("messageUUID") String messageUUID,
      @Param("expectedStatus") MessageProcessingStatus expectedStatus,
      @Param("targetStatus") MessageProcessingStatus targetStatus,
      @Param("processorID") String processorID,
      @Param("finishedAt") LocalDateTime finishedAt,
      @Param("lastError") String lastError);
}
