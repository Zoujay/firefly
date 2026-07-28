package firefly.service.messagecenter;

import firefly.constant.BuildStatus;
import firefly.dao.message.IPipelineMessageDao;
import firefly.support.FireflyIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

@FireflyIntegrationTest
@Transactional
class KafkaMessageJpaIntegrationTests {

    @Autowired
    private KafkaMessageStore kafkaMessageStore;

    @Autowired
    private IPipelineMessageDao pipelineMessageDao;

    @Test
    void savesTheSameBusinessUUIDOnlyOnceAcrossDifferentKafkaOffsets() {
        String messageUUID = BusinessMessageUUID.pipeline(9_999_999L, BuildStatus.RUNNING);
        String payload = "{\"messageUUID\":\"" + messageUUID + "\"}";

        kafkaMessageStore.savePipelineMessages(List.of(
                new ConsumerRecord<>("pipeline_message", 0, 9_999_991L, messageUUID, payload)
        ));
        kafkaMessageStore.savePipelineMessages(List.of(
                new ConsumerRecord<>("pipeline_message", 0, 9_999_992L, messageUUID, payload)
        ));

        assertEquals(
                1,
                pipelineMessageDao.countByMessageUUID(messageUUID)
        );
    }
}
