package firefly.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import firefly.github.dao.GitHubRepositorySubscriptionRepository;
import firefly.github.dao.GitHubWebhookDeliveryRepository;
import firefly.github.model.GitHubDeliveryStatus;
import firefly.github.model.GitHubRepositorySubscriptionEntity;
import firefly.github.model.GitHubSubscriptionStatus;
import firefly.github.model.GitHubWebhookDeliveryEntity;
import firefly.github.service.GitHubDeliveryWriteResult;
import firefly.github.service.GitHubWebhookDeliveryWriter;
import firefly.github.webhook.GitHubWebhookEvent;
import firefly.service.outbox.OutboxService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GitHubWebhookDeliveryWriterTests {

  @Mock private GitHubWebhookDeliveryRepository deliveryRepository;
  @Mock private GitHubRepositorySubscriptionRepository subscriptionRepository;
  @Mock private OutboxService outboxService;

  private GitHubWebhookDeliveryWriter writer;

  @BeforeEach
  void setUp() {
    when(deliveryRepository.saveAndFlush(any(GitHubWebhookDeliveryEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    writer =
        new GitHubWebhookDeliveryWriter(
            deliveryRepository,
            subscriptionRepository,
            outboxService,
            Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  void mismatchedPingIsPersistedAsRejected() {
    GitHubRepositorySubscriptionEntity subscription = subscription(null);

    GitHubDeliveryWriteResult result = writer.persist(subscription, ping(99L), "{}", 100L);

    assertTrue(result.rejected());
    ArgumentCaptor<GitHubWebhookDeliveryEntity> saved =
        ArgumentCaptor.forClass(GitHubWebhookDeliveryEntity.class);
    verify(deliveryRepository).save(saved.capture());
    assertEquals(GitHubDeliveryStatus.REJECTED, saved.getValue().getStatus());
  }

  @Test
  void unboundPingUsesConditionalDatabaseBinding() {
    GitHubRepositorySubscriptionEntity subscription = subscription(null);
    when(subscriptionRepository.bindWebhookIfUnbound(
            1L,
            99L,
            GitHubSubscriptionStatus.PROVISIONING,
            GitHubSubscriptionStatus.ACTIVE,
            java.time.LocalDateTime.of(2026, 8, 16, 0, 0)))
        .thenReturn(1);

    GitHubDeliveryWriteResult result = writer.persist(subscription, ping(99L), "{}", 99L);

    assertTrue(result.created());
    assertFalse(result.rejected());
    verify(subscriptionRepository)
        .bindWebhookIfUnbound(
            1L,
            99L,
            GitHubSubscriptionStatus.PROVISIONING,
            GitHubSubscriptionStatus.ACTIVE,
            java.time.LocalDateTime.of(2026, 8, 16, 0, 0));
  }

  @Test
  void concurrentDifferentHookCannotOverwriteWinner() {
    GitHubRepositorySubscriptionEntity subscription = subscription(null);
    when(subscriptionRepository.bindWebhookIfUnbound(
            1L,
            99L,
            GitHubSubscriptionStatus.PROVISIONING,
            GitHubSubscriptionStatus.ACTIVE,
            java.time.LocalDateTime.of(2026, 8, 16, 0, 0)))
        .thenReturn(0);
    when(subscriptionRepository.findById(1L))
        .thenReturn(Optional.of(subscription(100L).setStatus(GitHubSubscriptionStatus.ACTIVE)));

    GitHubDeliveryWriteResult result = writer.persist(subscription, ping(99L), "{}", 99L);

    assertTrue(result.rejected());
    assertEquals(100L, subscriptionRepository.findById(1L).orElseThrow().getWebhookId());
  }

  private GitHubRepositorySubscriptionEntity subscription(Long webhookId) {
    return new GitHubRepositorySubscriptionEntity()
        .setId(1L)
        .setPublicId("subscription")
        .setGithubRepositoryId(2L)
        .setWebhookId(webhookId)
        .setStatus(GitHubSubscriptionStatus.PROVISIONING);
  }

  private GitHubWebhookEvent ping(Long hookId) {
    return new GitHubWebhookEvent(
        "delivery",
        "ping",
        null,
        2L,
        "acme/repo",
        "https://github.com/acme/repo",
        "https://github.com/acme/repo.git",
        hookId,
        null,
        null,
        null,
        null,
        null,
        3L,
        "octocat",
        Instant.parse("2026-08-16T00:00:00Z"),
        false,
        null);
  }
}
