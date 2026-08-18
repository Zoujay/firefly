package firefly.service.messagecenter;

import com.fasterxml.jackson.databind.ObjectMapper;

import firefly.bean.dto.message.TriggerJobMessage;
import firefly.bean.dto.message.TriggerPipelineMessage;
import firefly.bean.dto.message.TriggerPluginMessage;
import firefly.bean.dto.message.TriggerStageMessage;
import firefly.constant.MessageCategory;
import firefly.constant.MessageProcessingStatus;
import firefly.model.message.KafkaMessage;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes one archived Inbox message in a single MySQL transaction.
 *
 * <p>The business state, every downstream Outbox insert, and the Inbox PROCESSING -> SUCCESS
 * transition commit together. Any exception rolls all of them back; the coordinator then records
 * FAILURE in a separate transaction.
 */
@Service
public class KafkaMessageProcessingTransaction {

    @Autowired private KafkaMessageStateService stateService;

    @Autowired private MessageCenter messageCenter;

    @Autowired private ObjectMapper objectMapper;

    @Transactional(rollbackFor = Exception.class)
    public void process(MessageCategory category, String messageUUID, String processorID)
            throws Exception {
        KafkaMessage archivedMessage = stateService.getRequired(category, messageUUID);
        if (archivedMessage.getProcessingStatus() != MessageProcessingStatus.PROCESSING
                || !StringUtils.equals(archivedMessage.getProcessorID(), processorID)) {
            throw new IllegalStateException(
                    "Kafka message is not owned by this processor: " + messageUUID);
        }

        dispatch(category, archivedMessage.getPayload());

        if (!stateService.markSuccess(category, messageUUID, processorID)) {
            throw new IllegalStateException(
                    "Failed to transition Inbox message to SUCCESS: " + messageUUID);
        }
    }

    private void dispatch(MessageCategory category, String payload) throws Exception {
        switch (category) {
            case PIPELINE ->
                    messageCenter.onPipelineMessage(
                            objectMapper.readValue(payload, TriggerPipelineMessage.class));
            case STAGE ->
                    messageCenter.onStageMessage(
                            objectMapper.readValue(payload, TriggerStageMessage.class));
            case JOB ->
                    messageCenter.onJobMessage(
                            objectMapper.readValue(payload, TriggerJobMessage.class));
            case PLUGIN ->
                    messageCenter.onPluginMessage(
                            objectMapper.readValue(payload, TriggerPluginMessage.class));
        }
    }
}
