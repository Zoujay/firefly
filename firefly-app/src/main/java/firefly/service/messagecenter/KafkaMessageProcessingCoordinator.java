package firefly.service.messagecenter;

import firefly.constant.MessageCategory;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Coordinates automatic and operator-triggered Inbox processing.
 *
 * <p>There is deliberately no Inbox poller. New records are processed once by the listener after
 * ACK. ARCHIVED, PROCESSING, or FAILURE records left by a crash or exception remain durable and are
 * resumed only through the manual recovery API.
 */
@Slf4j
@Service
public class KafkaMessageProcessingCoordinator {

    @Autowired
    private KafkaMessageStateService stateService;

    @Autowired
    private KafkaMessageProcessingTransaction processingTransaction;

    public boolean process(MessageCategory category, String messageUUID) {
        String processorID = UUID.randomUUID().toString();
        if (!stateService.claim(category, messageUUID, processorID)) {
            return false;
        }

        try {
            processingTransaction.process(category, messageUUID, processorID);
            return true;
        } catch (Exception exception) {
            boolean marked =
                stateService.markFailure(category, messageUUID, processorID, exception);
            if (!marked) {
                log.error(
                    "Failed to record Inbox processing failure: category={}, messageUUID={},"
                        + " processorID={}",
                    category,
                    messageUUID,
                    processorID,
                    exception);
            } else {
                log.error(
                    "Inbox processing failed and requires manual recovery: category={},"
                        + " messageUUID={}",
                    category,
                    messageUUID,
                    exception);
            }
            return false;
        }
    }
}
