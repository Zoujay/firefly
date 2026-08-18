package firefly.service.outbox;

import static firefly.constant.PersistenceDefaults.UNSET_TIME;

import firefly.bean.vo.response.OutboxEventResponse;
import firefly.constant.OutboxStatus;
import firefly.dao.outbox.IOutboxEventDao;
import firefly.model.outbox.OutboxEvent;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class OutboxStateService {

    @Autowired
    private IOutboxEventDao outboxEventDao;

    /**
     * Atomically performs PENDING/FAILED -> PUBLISHING in an independent transaction. Only the
     * caller whose conditional update affects one row may send the event, preventing concurrent
     * manual and after-commit sends.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<OutboxPublishTask> claim(Long outboxID, String publisherID) {
        int updated =
            outboxEventDao.claimForPublishing(
                outboxID,
                List.of(OutboxStatus.PENDING, OutboxStatus.FAILED),
                OutboxStatus.PUBLISHING,
                publisherID,
                LocalDateTime.now(),
                UNSET_TIME,
                StringUtils.EMPTY);
        if (updated != 1) {
            return Optional.empty();
        }
        OutboxEvent event = getRequired(outboxID);
        return Optional.of(
            new OutboxPublishTask(
                event.getId(),
                event.getTopic(),
                event.getMessageKey(),
                event.getPayload(),
                publisherID));
    }

    /**
     * Completes the normal PUBLISHING -> SENT transition. Matching the publisher ID prevents an old
     * worker from completing a newer attempt.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markSent(Long outboxID, String publisherID) {
        return outboxEventDao.markSent(
            outboxID,
            OutboxStatus.PUBLISHING,
            OutboxStatus.SENT,
            publisherID,
            LocalDateTime.now(),
            StringUtils.EMPTY)
            == 1;
    }

    /**
     * Persists PUBLISHING -> FAILED after Kafka reports an error. FAILED is a terminal automatic
     * state here; an operator explicitly starts any retry.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(Long outboxID, String publisherID, Exception exception) {
        String error =
            StringUtils.left(
                StringUtils.defaultIfBlank(
                    exception.getMessage(), exception.getClass().getName()),
                2048);
        return outboxEventDao.markFailed(
            outboxID,
            OutboxStatus.PUBLISHING,
            OutboxStatus.FAILED,
            publisherID,
            LocalDateTime.now(),
            error)
            == 1;
    }

    /**
     * Converts an abandoned PUBLISHING row to FAILED after an operator has confirmed that its
     * publisher is no longer active.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean resetPublishing(Long outboxID, String expectedPublisherID, String reason) {
        String error = StringUtils.left(StringUtils.defaultIfBlank(reason, "MANUAL_RESET"), 2048);
        return outboxEventDao.markFailed(
            outboxID,
            OutboxStatus.PUBLISHING,
            OutboxStatus.FAILED,
            expectedPublisherID,
            LocalDateTime.now(),
            error)
            == 1;
    }

    @Transactional(readOnly = true)
    public OutboxEvent getRequired(Long outboxID) {
        return outboxEventDao
            .findById(outboxID)
            .orElseThrow(() -> new OutboxEventNotFoundException(outboxID));
    }

    @Transactional(readOnly = true)
    public OutboxEventResponse getResponse(Long outboxID) {
        return OutboxEventResponse.from(getRequired(outboxID));
    }

    @Transactional(readOnly = true)
    public Page<OutboxEventResponse> getResponses(OutboxStatus status, Pageable pageable) {
        return outboxEventDao
            .findByPublishStatusOrderByCreatedAtAsc(status, pageable)
            .map(OutboxEventResponse::from);
    }
}
