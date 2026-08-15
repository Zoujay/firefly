package firefly.service.trigger;

import firefly.bean.dto.message.GithubMessageEntity;
import firefly.bean.dto.message.TriggerPipelineMessage;
import firefly.bean.dto.message.VolcanoMessageEntity;
import firefly.constant.BuildStatus;
import firefly.constant.TriggerOrigin;
import firefly.dao.triggermessage.IGithubTriggerDao;
import firefly.dao.triggermessage.IVolcanoTriggerDao;
import firefly.model.trigger.GithubTriggerEntity;
import firefly.model.trigger.VolcanoTriggerEntity;
import firefly.service.messagecenter.BusinessMessageUUID;
import firefly.service.outbox.OutboxService;
import firefly.service.trigger.impl.GithubTrigger;
import firefly.service.trigger.impl.VolcanoTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static firefly.constant.KafkaConfiguration.PIPELINE_TOPIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TriggerImplementationsTests {

    @Mock
    private IVolcanoTriggerDao volcanoTriggerDao;

    @Mock
    private IGithubTriggerDao githubTriggerDao;

    @Mock
    private OutboxService outboxService;

    @InjectMocks
    private VolcanoTrigger volcanoTrigger;

    @InjectMocks
    private GithubTrigger githubTrigger;

    @BeforeEach
    void injectAbstractTriggerDependency() {
        ReflectionTestUtils.setField(
                volcanoTrigger,
                "outboxService",
                outboxService
        );
        ReflectionTestUtils.setField(
                githubTrigger,
                "outboxService",
                outboxService
        );
    }

    @Test
    void savesVolcanoTriggerAndPublishesPipelineMessage() {
        when(volcanoTriggerDao.save(any(VolcanoTriggerEntity.class)))
                .thenAnswer(invocation -> withID(
                        invocation.getArgument(0),
                        101L
                ));
        VolcanoMessageEntity message = volcanoMessage();

        volcanoTrigger.dispatch(message);

        ArgumentCaptor<VolcanoTriggerEntity> entityCaptor =
                ArgumentCaptor.forClass(VolcanoTriggerEntity.class);
        verify(volcanoTriggerDao).save(entityCaptor.capture());
        assertEquals(10L, entityCaptor.getValue().getPipelineID());
        assertEquals("ak", entityCaptor.getValue().getAk());
        assertEquals("sk", entityCaptor.getValue().getSk());
        assertEquals(101L, message.getTriggerID());
        assertPublishedPipelineMessage(20L, 10L, 2);
    }

    @Test
    void savesGithubTriggerAndPublishesPipelineMessage() {
        when(githubTriggerDao.save(any(GithubTriggerEntity.class)))
                .thenAnswer(invocation -> withID(
                        invocation.getArgument(0),
                        102L
                ));
        GithubMessageEntity message = new GithubMessageEntity();
        message.setRepositoryUrl("https://github.com/example/repository");
        message.setPipelineID(11L)
                .setPipelineBuildID(21L)
                .setExecutionAttempt(0)
                .setTriggerOrigin(TriggerOrigin.GITHUB);

        githubTrigger.dispatch(message);

        ArgumentCaptor<GithubTriggerEntity> entityCaptor =
                ArgumentCaptor.forClass(GithubTriggerEntity.class);
        verify(githubTriggerDao).save(entityCaptor.capture());
        assertEquals(
                "https://github.com/example/repository",
                entityCaptor.getValue().getGithubRepoURL()
        );
        assertEquals(102L, message.getTriggerID());
        assertPublishedPipelineMessage(21L, 11L, 0);
    }

    @Test
    void rejectsAMessageWhoseRuntimeTypeDoesNotMatchTheTrigger() {
        GithubMessageEntity message = new GithubMessageEntity();
        message.setPipelineID(10L)
                .setPipelineBuildID(20L)
                .setExecutionAttempt(0)
                .setTriggerOrigin(TriggerOrigin.VOLCANO);

        assertThrows(
                IllegalArgumentException.class,
                () -> volcanoTrigger.dispatch(message)
        );
        verifyNoInteractions(volcanoTriggerDao, outboxService);
    }

    @Test
    void suppressesOutboxWhenTheDatabaseDoesNotGenerateATriggerId() {
        when(volcanoTriggerDao.save(any(VolcanoTriggerEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThrows(
                IllegalStateException.class,
                () -> volcanoTrigger.dispatch(volcanoMessage())
        );
        verifyNoInteractions(outboxService);
    }

    private VolcanoMessageEntity volcanoMessage() {
        VolcanoMessageEntity message = new VolcanoMessageEntity();
        message.setAk("ak");
        message.setSk("sk");
        message.setPipelineID(10L)
                .setPipelineBuildID(20L)
                .setExecutionAttempt(2)
                .setTriggerOrigin(TriggerOrigin.VOLCANO);
        return message;
    }

    private <T> T withID(T entity, Long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    private void assertPublishedPipelineMessage(
            Long pipelineBuildID,
            Long pipelineID,
            Integer executionAttempt
    ) {
        ArgumentCaptor<TriggerPipelineMessage> messageCaptor =
                ArgumentCaptor.forClass(TriggerPipelineMessage.class);
        verify(outboxService).enqueue(
                eq(PIPELINE_TOPIC),
                messageCaptor.capture()
        );
        TriggerPipelineMessage published = messageCaptor.getValue();
        assertEquals(pipelineID, published.getPipelineID());
        assertEquals(pipelineBuildID, published.getPipelineBuildID());
        assertEquals(executionAttempt, published.getExecutionAttempt());
        assertEquals(BuildStatus.RUNNING, published.getBuildStatus());
        assertEquals(
                BusinessMessageUUID.pipeline(
                        pipelineBuildID,
                        executionAttempt,
                        BuildStatus.RUNNING
                ),
                published.getMessageUUID()
        );
    }
}
