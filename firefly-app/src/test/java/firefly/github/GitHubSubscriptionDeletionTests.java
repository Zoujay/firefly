package firefly.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;

import firefly.github.api.GitHubApiClient;
import firefly.github.config.GitHubProperties;
import firefly.github.dao.GitHubConnectionRepository;
import firefly.github.dao.GitHubRepositorySubscriptionRepository;
import firefly.github.dao.GitHubTriggerConfigRepository;
import firefly.github.http.GitHubIntegrationException;
import firefly.github.model.GitHubConnectionEntity;
import firefly.github.model.GitHubConnectionStatus;
import firefly.github.model.GitHubRegistrationMode;
import firefly.github.model.GitHubRepositorySubscriptionEntity;
import firefly.github.model.GitHubSubscriptionStatus;
import firefly.github.model.GitHubTriggerConfigEntity;
import firefly.github.security.GitHubSecretCipher;
import firefly.github.service.GitHubSubscriptionDeletionStateService;
import firefly.github.service.GitHubSubscriptionDeletionTarget;
import firefly.github.service.GitHubSubscriptionService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class GitHubSubscriptionDeletionTests {

    private static final Clock CLOCK =
        Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private GitHubConnectionRepository connectionRepository;
    @Mock
    private GitHubRepositorySubscriptionRepository subscriptionRepository;
    @Mock
    private GitHubTriggerConfigRepository triggerConfigRepository;
    @Mock
    private GitHubApiClient apiClient;
    @Mock
    private GitHubSecretCipher secretCipher;
    @Mock
    private GitHubSubscriptionDeletionStateService deletionStateService;

    @Test
    void manualDeletionRetainsRemoteWebhookAndMarksSubscriptionOrphaned() {
        when(deletionStateService.begin("subscription"))
            .thenReturn(
                new GitHubSubscriptionDeletionTarget(
                    1L,
                    "subscription",
                    2L,
                    "acme",
                    "repo",
                    3L,
                    GitHubRegistrationMode.MANUAL));

        GitHubIntegrationException exception =
            assertThrows(
                GitHubIntegrationException.class, () -> service().delete("subscription"));

        assertEquals("GITHUB_MANUAL_WEBHOOK_DELETE_REQUIRED", exception.getCode());
        verify(deletionStateService)
            .fail(
                1L,
                GitHubSubscriptionStatus.ORPHANED,
                "The manually registered GitHub webhook was retained; delete Hook 3 in"
                    + " GitHub");
        verify(deletionStateService, never()).complete(1L);
        verifyNoInteractions(apiClient);
    }

    @Test
    void beginDisablesEveryPipelineTriggerBeforeRemoteWork() {
        GitHubRepositorySubscriptionEntity subscription =
            new GitHubRepositorySubscriptionEntity()
                .setId(1L)
                .setPublicId("subscription")
                .setRegistrationMode(GitHubRegistrationMode.AUTO)
                .setStatus(GitHubSubscriptionStatus.ACTIVE);
        GitHubTriggerConfigEntity config = new GitHubTriggerConfigEntity().setEnabled(true);
        when(subscriptionRepository.findByPublicId("subscription"))
            .thenReturn(Optional.of(subscription));
        when(triggerConfigRepository.findAllBySubscriptionId(1L)).thenReturn(List.of(config));
        GitHubSubscriptionDeletionStateService stateService =
            new GitHubSubscriptionDeletionStateService(
                subscriptionRepository, triggerConfigRepository, CLOCK);

        stateService.begin("subscription");

        assertEquals(GitHubSubscriptionStatus.DELETING, subscription.getStatus());
        assertFalse(config.getEnabled());
        assertEquals("SUBSCRIPTION_DELETED", config.getDisabledReason());
        verify(triggerConfigRepository).saveAll(List.of(config));
    }

    @Test
    void automaticDeletionCallsGitHubBetweenCommittedLocalTransitions() {
        GitHubSubscriptionDeletionTarget target = automaticTarget();
        GitHubConnectionEntity connection =
            new GitHubConnectionEntity().setId(2L).setStatus(GitHubConnectionStatus.ACTIVE);
        when(deletionStateService.begin("subscription")).thenReturn(target);
        when(connectionRepository.findById(2L)).thenReturn(Optional.of(connection));
        when(secretCipher.decrypt(null, null, null)).thenReturn("token");

        service().delete("subscription");

        var order = inOrder(deletionStateService, apiClient);
        order.verify(deletionStateService).begin("subscription");
        order.verify(apiClient).deleteWebhook("token", "acme", "repo", 3L);
        order.verify(deletionStateService).complete(1L);
    }

    @Test
    void automaticDeletionFailureKeepsDeletingState() {
        GitHubConnectionEntity connection =
            new GitHubConnectionEntity().setId(2L).setStatus(GitHubConnectionStatus.ACTIVE);
        when(deletionStateService.begin("subscription")).thenReturn(automaticTarget());
        when(connectionRepository.findById(2L)).thenReturn(Optional.of(connection));
        when(secretCipher.decrypt(null, null, null)).thenReturn("token");
        RuntimeException failure = new IllegalStateException("GitHub unavailable");
        org.mockito.Mockito.doThrow(failure)
            .when(apiClient)
            .deleteWebhook("token", "acme", "repo", 3L);

        assertThrows(RuntimeException.class, () -> service().delete("subscription"));

        verify(deletionStateService)
            .fail(1L, GitHubSubscriptionStatus.DELETING, "GitHub unavailable");
        verify(deletionStateService, never()).complete(1L);
    }

    private GitHubSubscriptionDeletionTarget automaticTarget() {
        return new GitHubSubscriptionDeletionTarget(
            1L, "subscription", 2L, "acme", "repo", 3L, GitHubRegistrationMode.AUTO);
    }

    private GitHubSubscriptionService service() {
        return new GitHubSubscriptionService(
            connectionRepository,
            subscriptionRepository,
            apiClient,
            new GitHubProperties(),
            secretCipher,
            deletionStateService,
            new ObjectMapper(),
            new SecureRandom(),
            CLOCK);
    }
}
