package firefly.service.outbox;

import static firefly.constant.KafkaConfiguration.STAGE_TOPIC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import firefly.bean.dto.message.TriggerStageMessage;
import firefly.constant.BuildStatus;
import firefly.constant.OutboxStatus;
import firefly.dao.outbox.IOutboxEventDao;
import firefly.model.outbox.OutboxEvent;
import firefly.support.FireflyIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

@FireflyIntegrationTest
class OutboxIntegrationTests {

    @Autowired private OutboxService outboxService;

    @Autowired private OutboxStateService stateService;

    @Autowired private IOutboxEventDao outboxEventDao;

    @Autowired private TransactionTemplate transactionTemplate;

    @MockitoBean private OutboxPublisher outboxPublisher;

    @Test
    void mandatoryRejectsEnqueueWithoutAnExistingTransaction() {
        TriggerStageMessage message = message();

        assertThrows(
                IllegalTransactionStateException.class,
                () -> outboxService.enqueue(STAGE_TOPIC, message));

        assertTrue(outboxEventDao.findByMessageUUID(message.getMessageUUID()).isEmpty());
    }

    @Test
    void commitsOutboxWithBusinessTransactionAndPublishesAfterCommit() {
        TriggerStageMessage message = message();

        transactionTemplate.executeWithoutResult(
                status -> outboxService.enqueue(STAGE_TOPIC, message));

        OutboxEvent event =
                outboxEventDao.findByMessageUUID(message.getMessageUUID()).orElseThrow();
        assertNotNull(event.getId());
        assertTrue(event.getId() > 0);
        assertEquals(message.getMessageUUID(), event.getMessageUUID());
        assertEquals(OutboxStatus.PENDING, event.getPublishStatus());
        assertEquals("stage:" + message.getStageBuildID() + ":0", event.getMessageKey());
        verify(outboxPublisher, timeout(2_000)).publishOnce(event.getId());
    }

    @Test
    void rollsBackOutboxAndSuppressesAfterCommitPublication() {
        TriggerStageMessage message = message();

        assertThrows(
                IllegalStateException.class,
                () ->
                        transactionTemplate.executeWithoutResult(
                                status -> {
                                    outboxService.enqueue(STAGE_TOPIC, message);
                                    throw new IllegalStateException("rollback");
                                }));

        assertTrue(outboxEventDao.findByMessageUUID(message.getMessageUUID()).isEmpty());
        verify(outboxPublisher, never()).publishOnce(anyLong());
    }

    @Test
    void appliesConditionalPublicationStateTransitions() {
        TriggerStageMessage message = message();
        transactionTemplate.executeWithoutResult(
                status -> outboxService.enqueue(STAGE_TOPIC, message));
        Long outboxID =
                outboxEventDao.findByMessageUUID(message.getMessageUUID()).orElseThrow().getId();

        Optional<OutboxPublishTask> claimed = stateService.claim(outboxID, "publisher-1");
        assertTrue(claimed.isPresent());
        assertTrue(stateService.claim(outboxID, "publisher-2").isEmpty());
        assertFalse(stateService.markSent(outboxID, "publisher-2"));
        assertTrue(stateService.markSent(outboxID, "publisher-1"));
        assertEquals(
                OutboxStatus.SENT,
                outboxEventDao.findById(outboxID).orElseThrow().getPublishStatus());
    }

    @Test
    void keepsMessageUuidAsTheIdempotencyKey() {
        TriggerStageMessage message = message();

        transactionTemplate.executeWithoutResult(
                status -> outboxService.enqueue(STAGE_TOPIC, message));
        OutboxEvent first =
                outboxEventDao.findByMessageUUID(message.getMessageUUID()).orElseThrow();

        transactionTemplate.executeWithoutResult(
                status -> outboxService.enqueue(STAGE_TOPIC, message));
        OutboxEvent duplicate =
                outboxEventDao.findByMessageUUID(message.getMessageUUID()).orElseThrow();

        assertEquals(first.getId(), duplicate.getId());
        assertEquals(1, outboxEventDao.countByMessageUUID(message.getMessageUUID()));
        verify(outboxPublisher, timeout(2_000).times(1)).publishOnce(first.getId());
    }

    private TriggerStageMessage message() {
        return new TriggerStageMessage()
                .setMessageUUID(UUID.randomUUID().toString())
                .setStageBuildID(11L)
                .setBuildStatus(BuildStatus.RUNNING)
                .setExecutionAttempt(0);
    }
}
