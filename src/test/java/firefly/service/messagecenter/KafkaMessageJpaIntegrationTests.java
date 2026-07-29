package firefly.service.messagecenter;

import firefly.constant.BuildStatus;
import firefly.dao.message.IPipelineMessageDao;
import firefly.support.FireflyIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@FireflyIntegrationTest
class KafkaMessageJpaIntegrationTests {

    @Autowired
    private KafkaMessageStore kafkaMessageStore;

    @Autowired
    private IPipelineMessageDao pipelineMessageDao;

    @Test
    void savesTheSameBusinessUUIDOnlyOnceAcrossDifferentKafkaOffsets() {
        String messageUUID = BusinessMessageUUID.pipeline(9_999_999L, BuildStatus.RUNNING);
        String payload = "{\"messageUUID\":\"" + messageUUID + "\"}";

        KafkaMessageSaveResult firstResult = kafkaMessageStore.savePipelineMessages(List.of(
                new ConsumerRecord<>("pipeline_message", 0, 9_999_991L, messageUUID, payload)
        ));
        KafkaMessageSaveResult duplicateResult = kafkaMessageStore.savePipelineMessages(List.of(
                new ConsumerRecord<>("pipeline_message", 0, 9_999_992L, messageUUID, payload)
        ));

        assertEquals(1, firstResult.newMessages().size());
        assertEquals(0, duplicateResult.newMessages().size());
        assertEquals(1, duplicateResult.duplicateCount());
        assertEquals(
                1,
                pipelineMessageDao.countByMessageUUID(messageUUID)
        );
    }

    @Test
    void onlyOneConcurrentInsertCanClaimTheSameBusinessUUID() throws Exception {
        String messageUUID = BusinessMessageUUID.pipeline(9_999_998L, BuildStatus.RUNNING);
        String payload = "{\"messageUUID\":\"" + messageUUID + "\"}";
        ConsumerRecord<String, String> first =
                new ConsumerRecord<>("pipeline_message", 0, 9_999_981L, messageUUID, payload);
        ConsumerRecord<String, String> second =
                new ConsumerRecord<>("pipeline_message", 0, 9_999_982L, messageUUID, payload);

        KafkaMessageSaveResult firstResult;
        KafkaMessageSaveResult secondResult;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstTask = executor.submit(
                    () -> kafkaMessageStore.savePipelineMessages(List.of(first)));
            var secondTask = executor.submit(
                    () -> kafkaMessageStore.savePipelineMessages(List.of(second)));
            firstResult = firstTask.get();
            secondResult = secondTask.get();
        }

        assertEquals(
                1,
                firstResult.newMessages().size() + secondResult.newMessages().size()
        );
        assertEquals(
                1,
                firstResult.duplicateCount() + secondResult.duplicateCount()
        );
        assertEquals(1, pipelineMessageDao.countByMessageUUID(messageUUID));
    }
}
