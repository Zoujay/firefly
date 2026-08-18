package firefly.dao.outbox;

import firefly.constant.OutboxStatus;
import firefly.model.outbox.OutboxEvent;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface IOutboxEventDao extends JpaRepository<OutboxEvent, Long> {

    Optional<OutboxEvent> findByMessageUUID(String messageUUID);

    long countByMessageUUID(String messageUUID);

    @Query("select e.id from OutboxEvent e where e.messageUUID = :messageUUID")
    Optional<Long> findIDByMessageUUID(@Param("messageUUID") String messageUUID);

    Page<OutboxEvent> findByPublishStatusOrderByCreatedAtAsc(
        OutboxStatus publishStatus, Pageable pageable);

    /**
     * Inserts only a new business message UUID. MySQL returns zero for an ignored duplicate, so the
     * service does not schedule the same logical message for publication again.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value =
            """
                INSERT IGNORE INTO outbox_event (
                    message_uuid, topic, message_key, message_type, payload
                ) VALUES (
                    :messageUUID, :topic, :messageKey, :messageType, :payload
                )
                """,
        nativeQuery = true)
    int insertIfAbsent(
        @Param("messageUUID") String messageUUID,
        @Param("topic") String topic,
        @Param("messageKey") String messageKey,
        @Param("messageType") String messageType,
        @Param("payload") String payload);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
            update OutboxEvent e
            set e.publishStatus = :targetStatus,
                e.publishAttempt = e.publishAttempt + 1,
                e.publisherID = :publisherID,
                e.publishingStartedAt = :startedAt,
                e.publishingFinishedAt = :unfinishedAt,
                e.lastError = :emptyValue
            where e.id = :id
              and e.publishStatus in :expectedStatuses
            """)
    int claimForPublishing(
        @Param("id") Long id,
        @Param("expectedStatuses") List<OutboxStatus> expectedStatuses,
        @Param("targetStatus") OutboxStatus targetStatus,
        @Param("publisherID") String publisherID,
        @Param("startedAt") LocalDateTime startedAt,
        @Param("unfinishedAt") LocalDateTime unfinishedAt,
        @Param("emptyValue") String emptyValue);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
            update OutboxEvent e
            set e.publishStatus = :targetStatus,
                e.publishingFinishedAt = :finishedAt,
                e.lastError = :emptyValue
            where e.id = :id
              and e.publishStatus = :expectedStatus
              and e.publisherID = :publisherID
            """)
    int markSent(
        @Param("id") Long id,
        @Param("expectedStatus") OutboxStatus expectedStatus,
        @Param("targetStatus") OutboxStatus targetStatus,
        @Param("publisherID") String publisherID,
        @Param("finishedAt") LocalDateTime finishedAt,
        @Param("emptyValue") String emptyValue);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
            update OutboxEvent e
            set e.publishStatus = :targetStatus,
                e.publishingFinishedAt = :finishedAt,
                e.lastError = :lastError
            where e.id = :id
              and e.publishStatus = :expectedStatus
              and e.publisherID = :publisherID
            """)
    int markFailed(
        @Param("id") Long id,
        @Param("expectedStatus") OutboxStatus expectedStatus,
        @Param("targetStatus") OutboxStatus targetStatus,
        @Param("publisherID") String publisherID,
        @Param("finishedAt") LocalDateTime finishedAt,
        @Param("lastError") String lastError);
}
