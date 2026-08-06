package firefly.service.messagecenter;

import firefly.bean.dto.message.TriggerJobMessage;
import firefly.bean.dto.message.TriggerPipelineMessage;
import firefly.bean.dto.message.TriggerPluginMessage;
import firefly.bean.dto.message.TriggerStageMessage;
import firefly.constant.BuildStatus;
import firefly.constant.PluginType;
import firefly.dao.message.IJobMessageDao;
import firefly.dao.message.IPipelineMessageDao;
import firefly.dao.message.IPluginMessageDao;
import firefly.dao.message.IStageMessageDao;
import firefly.support.MySqlTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static firefly.constant.KafkaConfiguration.JOB_TOPIC;
import static firefly.constant.KafkaConfiguration.PIPELINE_TOPIC;
import static firefly.constant.KafkaConfiguration.PLUGIN_TOPIC;
import static firefly.constant.KafkaConfiguration.STAGE_TOPIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.group-id=firefly-message-flow-${random.uuid}"
})
@Import(MySqlTestcontainersConfiguration.class)
@EmbeddedKafka(
        partitions = 1,
        topics = {PIPELINE_TOPIC, STAGE_TOPIC, JOB_TOPIC, PLUGIN_TOPIC},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class MessageFlowIntegrationTests {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private IPipelineMessageDao pipelineMessageDao;

    @Autowired
    private IStageMessageDao stageMessageDao;

    @Autowired
    private IJobMessageDao jobMessageDao;

    @Autowired
    private IPluginMessageDao pluginMessageDao;

    @MockitoBean
    private MessageCenter messageCenter;

    @Test
    void routesAllKafkaTopicsThroughPersistenceAndTheProductionListenerWiring() throws Exception {
        TriggerPipelineMessage pipelineMessage = new TriggerPipelineMessage()
                .setMessageUUID(UUID.randomUUID().toString())
                .setPipelineID(1L)
                .setPipelineBuildID(10L)
                .setBuildStatus(BuildStatus.RUNNING);
        TriggerStageMessage stageMessage = new TriggerStageMessage()
                .setMessageUUID(UUID.randomUUID().toString())
                .setStageBuildID(20L)
                .setBuildStatus(BuildStatus.RUNNING);
        TriggerJobMessage jobMessage = new TriggerJobMessage()
                .setMessageUUID(UUID.randomUUID().toString())
                .setJobBuildID(30L)
                .setBuildStatus(BuildStatus.RUNNING);
        TriggerPluginMessage pluginMessage = new TriggerPluginMessage()
                .setMessageUUID(UUID.randomUUID().toString())
                .setPluginType(PluginType.TEXT)
                .setPluginBuildID(40L)
                .setStatus(BuildStatus.SUCCESS);

        kafkaTemplate.send(
                PIPELINE_TOPIC,
                pipelineMessage.getMessageUUID(),
                pipelineMessage
        ).get(10, TimeUnit.SECONDS);
        kafkaTemplate.send(
                STAGE_TOPIC,
                stageMessage.getMessageUUID(),
                stageMessage
        ).get(10, TimeUnit.SECONDS);
        kafkaTemplate.send(
                JOB_TOPIC,
                jobMessage.getMessageUUID(),
                jobMessage
        ).get(10, TimeUnit.SECONDS);
        kafkaTemplate.send(
                PLUGIN_TOPIC,
                pluginMessage.getMessageUUID(),
                pluginMessage
        ).get(10, TimeUnit.SECONDS);

        verify(messageCenter, timeout(15_000)).onPipelineMessage(pipelineMessage);
        verify(messageCenter, timeout(15_000)).onStageMessage(stageMessage);
        verify(messageCenter, timeout(15_000)).onJobMessage(jobMessage);
        verify(messageCenter, timeout(15_000)).onPluginMessage(pluginMessage);

        assertEquals(1, pipelineMessageDao.countByMessageUUID(pipelineMessage.getMessageUUID()));
        assertEquals(1, stageMessageDao.countByMessageUUID(stageMessage.getMessageUUID()));
        assertEquals(1, jobMessageDao.countByMessageUUID(jobMessage.getMessageUUID()));
        assertEquals(1, pluginMessageDao.countByMessageUUID(pluginMessage.getMessageUUID()));
    }
}
