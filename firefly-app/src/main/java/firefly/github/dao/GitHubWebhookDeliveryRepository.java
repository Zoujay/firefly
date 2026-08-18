package firefly.github.dao;

import firefly.github.model.GitHubDeliveryStatus;
import firefly.github.model.GitHubWebhookDeliveryEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface GitHubWebhookDeliveryRepository
    extends JpaRepository<GitHubWebhookDeliveryEntity, Long> {

    Optional<GitHubWebhookDeliveryEntity> findByDeliveryId(String deliveryId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
            update GitHubWebhookDeliveryEntity d
               set d.status = :processing,
                   d.processorId = :processorId,
                   d.processingStartedAt = :startedAt,
                   d.processingFinishedAt = null,
                   d.nextRetryAt = null,
                   d.processingAttempt = d.processingAttempt + 1
             where d.deliveryId = :deliveryId
               and d.status in (:received, :retryable)
               and (d.nextRetryAt is null or d.nextRetryAt <= :startedAt)
               and d.processingAttempt < :maxAttempts
            """)
    int claim(
        @Param("deliveryId") String deliveryId,
        @Param("processorId") String processorId,
        @Param("startedAt") LocalDateTime startedAt,
        @Param("processing") GitHubDeliveryStatus processing,
        @Param("received") GitHubDeliveryStatus received,
        @Param("retryable") GitHubDeliveryStatus retryable,
        @Param("maxAttempts") int maxAttempts);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
            update GitHubWebhookDeliveryEntity d
               set d.status = :finalStatus,
                   d.processorId = '',
                   d.lastError = :error,
                   d.processingFinishedAt = :finishedAt,
                   d.nextRetryAt = :nextRetryAt
             where d.deliveryId = :deliveryId
               and d.status = :processing
               and d.processorId = :processorId
            """)
    int finishOwned(
        @Param("deliveryId") String deliveryId,
        @Param("processorId") String processorId,
        @Param("processing") GitHubDeliveryStatus processing,
        @Param("finalStatus") GitHubDeliveryStatus finalStatus,
        @Param("error") String error,
        @Param("finishedAt") LocalDateTime finishedAt,
        @Param("nextRetryAt") LocalDateTime nextRetryAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
            update GitHubWebhookDeliveryEntity d
               set d.status = :retryable,
                   d.processorId = '',
                   d.processingStartedAt = null,
                   d.nextRetryAt = :retryAt,
                   d.lastError = :error
             where d.status = :processing
               and d.processingStartedAt < :expiredBefore
               and d.processingAttempt < :maxAttempts
            """)
    int recoverExpired(
        @Param("expiredBefore") LocalDateTime expiredBefore,
        @Param("retryAt") LocalDateTime retryAt,
        @Param("error") String error,
        @Param("processing") GitHubDeliveryStatus processing,
        @Param("retryable") GitHubDeliveryStatus retryable,
        @Param("maxAttempts") int maxAttempts);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
            update GitHubWebhookDeliveryEntity d
               set d.status = :dead,
                   d.processorId = '',
                   d.processingStartedAt = null,
                   d.processingFinishedAt = :finishedAt,
                   d.lastError = :error
             where d.status = :processing
               and d.processingStartedAt < :expiredBefore
               and d.processingAttempt >= :maxAttempts
            """)
    int expireDead(
        @Param("expiredBefore") LocalDateTime expiredBefore,
        @Param("finishedAt") LocalDateTime finishedAt,
        @Param("error") String error,
        @Param("processing") GitHubDeliveryStatus processing,
        @Param("dead") GitHubDeliveryStatus dead,
        @Param("maxAttempts") int maxAttempts);

    List<GitHubWebhookDeliveryEntity>
    findTop100ByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
        GitHubDeliveryStatus status, LocalDateTime nextRetryAt);
}
