package firefly.service.trigger;

import static firefly.constant.KafkaConfiguration.PIPELINE_TOPIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import firefly.bean.dto.message.TriggerPipelineMessage;
import firefly.bean.dto.message.VolcanoMessageEntity;
import firefly.constant.TriggerOrigin;
import firefly.dao.triggermessage.IGithubTriggerDao;
import firefly.dao.triggermessage.IVolcanoTriggerDao;
import firefly.model.trigger.GithubTriggerEntity;
import firefly.model.trigger.VolcanoTriggerEntity;
import firefly.service.outbox.OutboxService;
import firefly.service.trigger.impl.VolcanoTrigger;
import firefly.support.FireflyIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@FireflyIntegrationTest
class TriggerTransactionIntegrationTests {

  @Autowired private IGithubTriggerDao githubTriggerDao;

  @Autowired private IVolcanoTriggerDao volcanoTriggerDao;

  @Autowired private VolcanoTrigger volcanoTrigger;

  @MockitoBean private OutboxService outboxService;

  @Test
  void inheritsGeneratedIdsFromBaseTriggerEntity() {
    GithubTriggerEntity github =
        githubTriggerDao.saveAndFlush(
            new GithubTriggerEntity().setGithubRepoURL("https://github.com/example/repository"));
    VolcanoTriggerEntity volcano =
        volcanoTriggerDao.saveAndFlush(
            new VolcanoTriggerEntity().setPipelineID(100L).setAk("ak").setSk("sk"));

    try {
      assertNotNull(github.getId());
      assertTrue(github.getId() > 0);
      assertNotNull(volcano.getId());
      assertTrue(volcano.getId() > 0);
    } finally {
      githubTriggerDao.deleteById(github.getId());
      volcanoTriggerDao.deleteById(volcano.getId());
    }
  }

  @Test
  void rollsBackTriggerRecordWhenOutboxWriteFails() {
    long originalCount = volcanoTriggerDao.count();
    doThrow(new IllegalStateException("outbox failed"))
        .when(outboxService)
        .enqueue(eq(PIPELINE_TOPIC), any(TriggerPipelineMessage.class));
    VolcanoMessageEntity message = new VolcanoMessageEntity();
    message.setAk("rollback-ak");
    message.setSk("rollback-sk");
    message
        .setPipelineID(101L)
        .setPipelineBuildID(201L)
        .setExecutionAttempt(0)
        .setTriggerOrigin(TriggerOrigin.VOLCANO);

    assertThrows(IllegalStateException.class, () -> volcanoTrigger.dispatch(message));

    assertEquals(originalCount, volcanoTriggerDao.count());
  }
}
