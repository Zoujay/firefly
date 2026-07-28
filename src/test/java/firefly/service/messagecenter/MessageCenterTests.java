package firefly.service.messagecenter;

import firefly.bean.dto.StageBuildDto;
import firefly.bean.dto.StageConfigDto;
import firefly.bean.dto.message.TriggerPipelineMessage;
import firefly.bean.dto.message.TriggerStageMessage;
import firefly.constant.BuildStatus;
import firefly.service.jobbuild.IJobBuildService;
import firefly.service.jobconfig.IJobConfigService;
import firefly.service.jobconfig.IJobRelationService;
import firefly.service.pipelinebuild.IPipelineBuildService;
import firefly.service.pipelineconfig.IPipelineConfigService;
import firefly.service.stagebuild.IStageBuildService;
import firefly.service.stageconfig.IStageConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;

import static firefly.constant.KafkaConfiguration.PIPELINE_TOPIC;
import static firefly.constant.KafkaConfiguration.STAGE_TOPIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageCenterTests {

    @Mock
    private IPipelineBuildService pipelineBuildService;

    @Mock
    private IPipelineConfigService pipelineConfigService;

    @Mock
    private IStageConfigService stageConfigService;

    @Mock
    private IStageBuildService stageBuildService;

    @Mock
    private IJobBuildService jobBuildService;

    @Mock
    private IJobConfigService jobConfigService;

    @Mock
    private IJobRelationService jobRelationService;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private MessageCenter messageCenter;

    @Test
    void propagatesStageFailureToPipeline() {
        StageBuildDto stageBuild = new StageBuildDto()
                .setStageBuildID(10L)
                .setStageConfigID(20L)
                .setPipelineBuildID(30L)
                .setStatus(BuildStatus.RUNNING);
        StageConfigDto stageConfig = new StageConfigDto()
                .setId(20L)
                .setPipelineID(40L);
        TriggerStageMessage message = new TriggerStageMessage()
                .setMessageUUID("stage-failure")
                .setStageBuildID(10L)
                .setBuildStatus(BuildStatus.FAILURE);

        when(stageBuildService.getStageBuildByID(10L)).thenReturn(stageBuild);
        when(stageConfigService.getStageConfigByID(20L)).thenReturn(stageConfig);

        messageCenter.onStageMessage(message);

        ArgumentCaptor<TriggerPipelineMessage> captor =
                ArgumentCaptor.forClass(TriggerPipelineMessage.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(PIPELINE_TOPIC), keyCaptor.capture(), captor.capture());
        assertEquals(BuildStatus.FAILURE, captor.getValue().getBuildStatus());
        assertEquals(30L, captor.getValue().getPipelineBuildID());
        assertEquals(captor.getValue().getMessageUUID(), keyCaptor.getValue());
        assertEquals(
                BusinessMessageUUID.pipeline(30L, BuildStatus.FAILURE),
                captor.getValue().getMessageUUID()
        );
    }

    @Test
    void triggersNextOrderedStageInTheSamePipelineBuild() {
        StageBuildDto currentStageBuild = new StageBuildDto()
                .setStageBuildID(10L)
                .setStageConfigID(20L)
                .setPipelineBuildID(30L)
                .setStatus(BuildStatus.RUNNING);
        StageBuildDto nextStageBuild = new StageBuildDto()
                .setStageBuildID(11L)
                .setStageConfigID(21L)
                .setPipelineBuildID(30L)
                .setStatus(BuildStatus.PENDING);
        StageConfigDto currentStage = new StageConfigDto()
                .setId(20L)
                .setPipelineID(40L)
                .setStageOrder(0);
        StageConfigDto nextStage = new StageConfigDto()
                .setId(21L)
                .setPipelineID(40L)
                .setStageOrder(1);
        TriggerStageMessage message = new TriggerStageMessage()
                .setMessageUUID("stage-success")
                .setStageBuildID(10L)
                .setBuildStatus(BuildStatus.SUCCESS);

        when(stageBuildService.getStageBuildByID(10L)).thenReturn(currentStageBuild);
        when(stageConfigService.getStageConfigByID(20L)).thenReturn(currentStage);
        when(stageConfigService.getStageConfigsByPipelineID(40L))
                .thenReturn(List.of(currentStage, nextStage));
        when(stageBuildService.getStageBuildByStageConfigIDAndPipelineBuildID(21L, 30L))
                .thenReturn(nextStageBuild);

        messageCenter.onStageMessage(message);

        ArgumentCaptor<TriggerStageMessage> captor =
                ArgumentCaptor.forClass(TriggerStageMessage.class);
        verify(kafkaTemplate).send(eq(STAGE_TOPIC), anyString(), captor.capture());
        assertEquals(11L, captor.getValue().getStageBuildID());
        assertEquals(BuildStatus.RUNNING, captor.getValue().getBuildStatus());
        assertEquals(
                BusinessMessageUUID.stage(11L, BuildStatus.RUNNING),
                captor.getValue().getMessageUUID()
        );
    }
}
