package firefly.model.outbox;

import static firefly.constant.PersistenceDefaults.UNSET_TIME;

import firefly.constant.OutboxStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Durable record of a Kafka message that Firefly intends to publish.
 *
 * <p>Outbox solves the gap between changing MySQL state and sending Kafka: the business changes and
 * this row are committed in one database transaction. Kafka publication happens only after that
 * commit. A crash or send failure therefore leaves an explicit PENDING/PUBLISHING/FAILED row for
 * manual recovery instead of silently losing the downstream event.
 */
@Getter
@Entity
@Table(
        name = "outbox_event",
        uniqueConstraints =
                @UniqueConstraint(name = "uidx_outbox_message_uuid", columnNames = "message_uuid"),
        indexes =
                @Index(
                        name = "idx_outbox_publish_status",
                        columnList = "publish_status,created_at,id"))
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /**
     * Stable business idempotency key. The numeric ID is only the database identity; duplicate
     * logical events are still rejected by this UUID.
     */
    @Column(name = "message_uuid", nullable = false, updatable = false, length = 36)
    private String messageUUID = StringUtils.EMPTY;

    @Column(name = "topic", nullable = false, updatable = false, length = 249)
    private String topic = StringUtils.EMPTY;

    @Column(name = "message_key", nullable = false, updatable = false, length = 128)
    private String messageKey = StringUtils.EMPTY;

    @Column(name = "message_type", nullable = false, updatable = false, length = 128)
    private String messageType = StringUtils.EMPTY;

    @Lob
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload = StringUtils.EMPTY;

    @Enumerated(EnumType.STRING)
    @Column(name = "publish_status", nullable = false, length = 32)
    private OutboxStatus publishStatus = OutboxStatus.PENDING;

    @Column(name = "publish_attempt", nullable = false)
    private Integer publishAttempt = 0;

    @Column(name = "publisher_id", nullable = false, length = 128)
    private String publisherID = StringUtils.EMPTY;

    @Column(name = "publishing_started_at", nullable = false)
    private LocalDateTime publishingStartedAt = UNSET_TIME;

    @Column(name = "publishing_finished_at", nullable = false)
    private LocalDateTime publishingFinishedAt = UNSET_TIME;

    @Column(name = "last_error", nullable = false, length = 2048)
    private String lastError = StringUtils.EMPTY;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
