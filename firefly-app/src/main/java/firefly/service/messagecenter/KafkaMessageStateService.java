package firefly.service.messagecenter;

import static firefly.constant.PersistenceDefaults.UNSET_TIME;

import firefly.bean.vo.response.KafkaMessageProcessingResponse;
import firefly.constant.MessageCategory;
import firefly.constant.MessageProcessingStatus;
import firefly.dao.message.IJobMessageDao;
import firefly.dao.message.IKafkaMessageDao;
import firefly.dao.message.IPipelineMessageDao;
import firefly.dao.message.IPluginMessageDao;
import firefly.dao.message.IStageMessageDao;
import firefly.model.message.KafkaMessage;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class KafkaMessageStateService {

    @Autowired private IPipelineMessageDao pipelineMessageDao;

    @Autowired private IStageMessageDao stageMessageDao;

    @Autowired private IJobMessageDao jobMessageDao;

    @Autowired private IPluginMessageDao pluginMessageDao;

    /**
     * Atomically changes ARCHIVED or FAILURE to PROCESSING.
     *
     * <p>The claim is committed before business processing starts so an application crash remains
     * visible as PROCESSING. Because this is a conditional database update, concurrent automatic or
     * manual attempts for the same UUID cannot both execute the business handler.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(MessageCategory category, String messageUUID, String processorID) {
        int updated =
                dao(category)
                        .claimForProcessing(
                                messageUUID,
                                List.of(
                                        MessageProcessingStatus.ARCHIVED,
                                        MessageProcessingStatus.FAILURE),
                                MessageProcessingStatus.PROCESSING,
                                processorID,
                                LocalDateTime.now(),
                                UNSET_TIME,
                                StringUtils.EMPTY);
        return updated == 1;
    }

    /**
     * Joins the business transaction and records PROCESSING -> SUCCESS.
     *
     * <p>MANDATORY is intentional: the success marker must never commit in a standalone
     * transaction. It must commit or roll back together with the Pipeline/Stage/Job/Plugin changes
     * and the downstream Outbox insert.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markSuccess(MessageCategory category, String messageUUID, String processorID) {
        int updated =
                dao(category)
                        .markProcessingSuccess(
                                messageUUID,
                                MessageProcessingStatus.PROCESSING,
                                MessageProcessingStatus.SUCCESS,
                                processorID,
                                LocalDateTime.now(),
                                StringUtils.EMPTY);
        return updated == 1;
    }

    /**
     * Records PROCESSING -> FAILURE after the business transaction has rolled back. A new
     * transaction is required so the failure evidence survives.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailure(
            MessageCategory category, String messageUUID, String processorID, Exception exception) {
        String error =
                StringUtils.defaultIfBlank(exception.getMessage(), exception.getClass().getName());
        error = StringUtils.left(error, 2048);
        int updated =
                dao(category)
                        .markProcessingFailure(
                                messageUUID,
                                MessageProcessingStatus.PROCESSING,
                                MessageProcessingStatus.FAILURE,
                                processorID,
                                LocalDateTime.now(),
                                error);
        return updated == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean resetProcessing(
            MessageCategory category,
            String messageUUID,
            String expectedProcessorID,
            String reason) {
        String error = StringUtils.left(StringUtils.defaultIfBlank(reason, "MANUAL_RESET"), 2048);
        int updated =
                dao(category)
                        .markProcessingFailure(
                                messageUUID,
                                MessageProcessingStatus.PROCESSING,
                                MessageProcessingStatus.FAILURE,
                                expectedProcessorID,
                                LocalDateTime.now(),
                                error);
        return updated == 1;
    }

    @Transactional(readOnly = true)
    public KafkaMessage getRequired(MessageCategory category, String messageUUID) {
        return dao(category)
                .findByMessageUUID(messageUUID)
                .orElseThrow(() -> new KafkaMessageNotFoundException(category, messageUUID));
    }

    @Transactional(readOnly = true)
    public KafkaMessageProcessingResponse getResponse(
            MessageCategory category, String messageUUID) {
        return KafkaMessageProcessingResponse.from(category, getRequired(category, messageUUID));
    }

    @Transactional(readOnly = true)
    public Page<KafkaMessageProcessingResponse> getResponses(
            MessageCategory category, MessageProcessingStatus status, Pageable pageable) {
        return dao(category)
                .findByProcessingStatusOrderByReceivedAtAsc(status, pageable)
                .map(message -> KafkaMessageProcessingResponse.from(category, message));
    }

    private IKafkaMessageDao<? extends KafkaMessage> dao(MessageCategory category) {
        return switch (category) {
            case PIPELINE -> pipelineMessageDao;
            case STAGE -> stageMessageDao;
            case JOB -> jobMessageDao;
            case PLUGIN -> pluginMessageDao;
        };
    }
}
