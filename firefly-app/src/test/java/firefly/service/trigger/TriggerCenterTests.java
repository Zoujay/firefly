package firefly.service.trigger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import firefly.bean.dto.message.GithubMessageEntity;
import firefly.bean.dto.message.VolcanoMessageEntity;
import firefly.constant.TriggerOrigin;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TriggerCenterTests {

  @Mock private ITrigger<VolcanoMessageEntity> volcanoTrigger;

  @Mock private ITrigger<GithubMessageEntity> githubTrigger;

  @Test
  void dispatchesToTheTriggerRegisteredForTheMessageOrigin() {
    when(volcanoTrigger.getTriggerOrigin()).thenReturn(TriggerOrigin.VOLCANO);
    when(githubTrigger.getTriggerOrigin()).thenReturn(TriggerOrigin.GITHUB);
    TriggerCenter triggerCenter = new TriggerCenter(List.of(volcanoTrigger, githubTrigger));
    VolcanoMessageEntity message = new VolcanoMessageEntity();
    message.setTriggerOrigin(TriggerOrigin.VOLCANO);

    triggerCenter.dispatch(message);

    verify(volcanoTrigger).dispatch(message);
    verify(githubTrigger, never()).dispatch(message);
  }

  @Test
  void rejectsAnUnsupportedTriggerOrigin() {
    when(volcanoTrigger.getTriggerOrigin()).thenReturn(TriggerOrigin.VOLCANO);
    TriggerCenter triggerCenter = new TriggerCenter(List.of(volcanoTrigger));
    GithubMessageEntity message = new GithubMessageEntity();
    message.setTriggerOrigin(TriggerOrigin.GITHUB);

    assertThrows(IllegalStateException.class, () -> triggerCenter.dispatch(message));
  }

  @Test
  void rejectsDuplicateTriggerImplementations() {
    when(volcanoTrigger.getTriggerOrigin()).thenReturn(TriggerOrigin.VOLCANO);
    when(githubTrigger.getTriggerOrigin()).thenReturn(TriggerOrigin.VOLCANO);

    assertThrows(
        IllegalStateException.class,
        () -> new TriggerCenter(List.of(volcanoTrigger, githubTrigger)));
  }

  @Test
  void rejectsAMessageWithoutAnOrigin() {
    TriggerCenter triggerCenter = new TriggerCenter(List.of());

    assertThrows(
        IllegalArgumentException.class, () -> triggerCenter.dispatch(new VolcanoMessageEntity()));
  }
}
