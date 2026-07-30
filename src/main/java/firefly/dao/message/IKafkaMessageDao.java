package firefly.dao.message;

import firefly.constant.MessageProcessingStatus;
import firefly.model.message.KafkaMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.NoRepositoryBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@NoRepositoryBean
public interface IKafkaMessageDao<T extends KafkaMessage> extends JpaRepository<T, Long> {

    long countByMessageUUID(String messageUUID);

    Optional<T> findByMessageUUID(String messageUUID);

    Page<T> findByProcessingStatusOrderByReceivedAtAsc(
            MessageProcessingStatus processingStatus,
            Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update #{#entityName} m
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
            @Param("emptyValue") String emptyValue
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update #{#entityName} m
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
            @Param("emptyValue") String emptyValue
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update #{#entityName} m
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
            @Param("lastError") String lastError
    );
}
