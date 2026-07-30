package firefly.service.outbox;

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

import static firefly.constant.KafkaConfiguration.STAGE_TOPIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@FireflyIntegrationTest
class OutboxIntegrationTests {

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxStateService stateService;

    @Autowired
    private IOutboxEventDao outboxEventDao;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private OutboxPublisher outboxPublisher;

    @Test
    void mandatoryRejectsEnqueueWithoutAnExistingTransaction() {
        TriggerStageMessage message = message();

        assertThrows(
                IllegalTransactionStateException.class,
                () -> outboxService.enqueue(STAGE_TOPIC, message)
        );

        assertFalse(outboxEventDao.existsById(message.getMessageUUID()));
    }

    @Test
    void commitsOutboxWithBusinessTransactionAndPublishesAfterCommit() {
        TriggerStageMessage message = message();

        transactionTemplate.executeWithoutResult(
                status -> outboxService.enqueue(STAGE_TOPIC, message)
        );

        OutboxEvent event = outboxEventDao
                .findById(message.getMessageUUID())
                .orElseThrow();
        assertEquals(OutboxStatus.PENDING, event.getPublishStatus());
        assertEquals(
                "stage:" + message.getStageBuildID() + ":0",
                event.getMessageKey()
        );
        verify(outboxPublisher, timeout(2_000))
                .publishOnce(message.getMessageUUID());
    }

    @Test
    void rollsBackOutboxAndSuppressesAfterCommitPublication() {
        TriggerStageMessage message = message();

        assertThrows(
                IllegalStateException.class,
                () -> transactionTemplate.executeWithoutResult(status -> {
                    outboxService.enqueue(STAGE_TOPIC, message);
                    throw new IllegalStateException("rollback");
                })
        );

        assertFalse(outboxEventDao.existsById(message.getMessageUUID()));
        verify(outboxPublisher, never())
                .publishOnce(message.getMessageUUID());
    }

    @Test
    void appliesConditionalPublicationStateTransitions() {
        TriggerStageMessage message = message();
        transactionTemplate.executeWithoutResult(
                status -> outboxService.enqueue(STAGE_TOPIC, message)
        );

        Optional<OutboxPublishTask> claimed =
                stateService.claim(message.getMessageUUID(), "publisher-1");
        assertTrue(claimed.isPresent());
        assertTrue(stateService
                .claim(message.getMessageUUID(), "publisher-2")
                .isEmpty());
        assertFalse(stateService.markSent(
                message.getMessageUUID(),
                "publisher-2"
        ));
        assertTrue(stateService.markSent(
                message.getMessageUUID(),
                "publisher-1"
        ));
        assertEquals(
                OutboxStatus.SENT,
                outboxEventDao.findById(message.getMessageUUID())
                        .orElseThrow()
                        .getPublishStatus()
        );
    }

    private TriggerStageMessage message() {
        return new TriggerStageMessage()
                .setMessageUUID(UUID.randomUUID().toString())
                .setStageBuildID(11L)
                .setBuildStatus(BuildStatus.RUNNING)
                .setExecutionAttempt(0);
    }
}
