package firefly.github.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import firefly.github.api.GitHubApiClient;
import firefly.github.api.GitHubRepository;
import firefly.github.api.GitHubWebhook;
import firefly.github.config.GitHubProperties;
import firefly.github.dao.GitHubConnectionRepository;
import firefly.github.dao.GitHubRepositorySubscriptionRepository;
import firefly.github.dto.GitHubSubscriptionRequest;
import firefly.github.dto.GitHubSubscriptionResponse;
import firefly.github.http.GitHubIntegrationException;
import firefly.github.model.GitHubConnectionEntity;
import firefly.github.model.GitHubConnectionStatus;
import firefly.github.model.GitHubRegistrationMode;
import firefly.github.model.GitHubRepositorySubscriptionEntity;
import firefly.github.model.GitHubSubscriptionStatus;
import firefly.github.security.EncryptedSecret;
import firefly.github.security.GitHubSecretCipher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class GitHubSubscriptionService {

    private static final Set<String> SUPPORTED_EVENTS = Set.of("push", "pull_request");
    private final GitHubConnectionRepository connectionRepository;
    private final GitHubRepositorySubscriptionRepository subscriptionRepository;
    private final GitHubApiClient apiClient;
    private final GitHubProperties properties;
    private final GitHubSecretCipher secretCipher;
    private final GitHubSubscriptionDeletionStateService deletionStateService;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom;
    private final Clock clock;

    public GitHubSubscriptionService(
            GitHubConnectionRepository connectionRepository,
            GitHubRepositorySubscriptionRepository subscriptionRepository,
            GitHubApiClient apiClient,
            GitHubProperties properties,
            GitHubSecretCipher secretCipher,
            GitHubSubscriptionDeletionStateService deletionStateService,
            ObjectMapper objectMapper,
            SecureRandom secureRandom,
            Clock clock
    ) {
        this.connectionRepository = connectionRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.apiClient = apiClient;
        this.properties = properties;
        this.secretCipher = secretCipher;
        this.deletionStateService = deletionStateService;
        this.objectMapper = objectMapper;
        this.secureRandom = secureRandom;
        this.clock = clock;
    }

    public List<GitHubRepository> repositories(String connectionPublicId) {
        GitHubConnectionEntity connection = activeConnection(connectionPublicId);
        return apiClient.listRepositories(token(connection));
    }

    public GitHubSubscriptionResponse upsert(
            String connectionPublicId,
            String owner,
            String repositoryName,
            GitHubSubscriptionRequest request
    ) {
        GitHubConnectionEntity connection = activeConnection(connectionPublicId);
        GitHubRepository repository = apiClient.getRepository(
                token(connection),
                owner,
                repositoryName
        );
        List<String> events = normalizeEvents(request.events());
        GitHubRepositorySubscriptionEntity existing = subscriptionRepository
                .findByGithubRepositoryId(repository.id())
                .orElse(null);
        if (existing != null) {
            if (existing.getRegistrationMode() != request.registrationMode()) {
                throw conflict("Subscription registration mode already exists for this repository");
            }
            if (!connection.getId().equals(existing.getConnectionId())) {
                throw conflict("Subscription belongs to a different GitHub connection");
            }
            updateRepository(existing, repository, owner, repositoryName, events);
            subscriptionRepository.saveAndFlush(existing);
            if (request.registrationMode() == GitHubRegistrationMode.AUTO) {
                return provisionAuto(connection, existing, webhookSecret(existing), true);
            }
            return response(existing, null);
        }

        String webhookSecret = randomSecret();
        EncryptedSecret encrypted = secretCipher.encrypt(webhookSecret);
        LocalDateTime now = now();
        GitHubRepositorySubscriptionEntity subscription = new GitHubRepositorySubscriptionEntity()
                .setPublicId(UUID.randomUUID().toString())
                .setConnectionId(connection.getId())
                .setGithubRepositoryId(repository.id())
                .setNodeId(repository.nodeId())
                .setOwner(owner)
                .setRepositoryName(repositoryName)
                .setFullName(repository.fullName())
                .setHtmlUrl(repository.htmlUrl())
                .setCloneUrl(repository.cloneUrl())
                .setDefaultBranch(repository.defaultBranch())
                .setRegistrationMode(request.registrationMode())
                .setWebhookSecretCiphertext(encrypted.ciphertext())
                .setWebhookSecretNonce(encrypted.nonce())
                .setWebhookSecretKeyVersion(encrypted.keyVersion())
                .setEvents(writeEvents(events))
                .setStatus(GitHubSubscriptionStatus.PROVISIONING)
                .setLastError("")
                .setCreatedAt(now)
                .setUpdatedAt(now);
        try {
            subscription = subscriptionRepository.saveAndFlush(subscription);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("A GitHub subscription already exists for this repository");
        }

        if (request.registrationMode() == GitHubRegistrationMode.MANUAL) {
            return response(subscription, webhookSecret);
        }
        return provisionAuto(connection, subscription, webhookSecret, false);
    }

    private GitHubSubscriptionResponse provisionAuto(
            GitHubConnectionEntity connection,
            GitHubRepositorySubscriptionEntity subscription,
            String webhookSecret,
            boolean allowReconcile
    ) {
        if (properties.getWebhookCallbackUrl() == null) {
            subscription.setStatus(GitHubSubscriptionStatus.ERROR)
                    .setLastError("Webhook callback URL is not configured")
                    .setUpdatedAt(now());
            subscriptionRepository.save(subscription);
            throw new GitHubIntegrationException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "GITHUB_WEBHOOK_NOT_CONFIGURED",
                    "GitHub webhook callback URL is not configured"
            );
        }

        List<String> events = readEvents(subscription.getEvents());
        GitHubWebhook webhook;
        if (subscription.getWebhookId() != null) {
            webhook = apiClient.updateWebhook(
                    token(connection),
                    subscription.getOwner(),
                    subscription.getRepositoryName(),
                    subscription.getWebhookId(),
                    properties.getWebhookCallbackUrl(),
                    webhookSecret,
                    events
            );
        } else {
            List<GitHubWebhook> matching = matchingCallbacks(connection, subscription);
            if (matching.isEmpty()) {
                webhook = apiClient.createWebhook(
                        token(connection),
                        subscription.getOwner(),
                        subscription.getRepositoryName(),
                        properties.getWebhookCallbackUrl(),
                        webhookSecret,
                        events
                );
            } else if (allowReconcile && matching.size() == 1) {
                webhook = apiClient.updateWebhook(
                        token(connection),
                        subscription.getOwner(),
                        subscription.getRepositoryName(),
                        matching.getFirst().id(),
                        properties.getWebhookCallbackUrl(),
                        webhookSecret,
                        events
                );
            } else {
                throw conflict("A GitHub webhook already uses the Firefly callback URL");
            }
        }
        subscription.setWebhookId(webhook.id())
                .setStatus(GitHubSubscriptionStatus.PROVISIONING)
                .setLastError("")
                .setUpdatedAt(now());
        subscriptionRepository.saveAndFlush(subscription);
        apiClient.pingWebhook(
                token(connection),
                subscription.getOwner(),
                subscription.getRepositoryName(),
                webhook.id()
        );
        return response(subscription, null);
    }

    public void ping(String subscriptionPublicId) {
        GitHubRepositorySubscriptionEntity subscription = subscription(subscriptionPublicId);
        if (subscription.getWebhookId() == null) {
            throw new GitHubIntegrationException(
                    HttpStatus.CONFLICT,
                    "GITHUB_WEBHOOK_NOT_BOUND",
                    "GitHub webhook ID is not bound yet"
            );
        }
        GitHubConnectionEntity connection = activeConnection(subscription.getConnectionId());
        apiClient.pingWebhook(
                token(connection),
                subscription.getOwner(),
                subscription.getRepositoryName(),
                subscription.getWebhookId()
        );
    }

    public void delete(String subscriptionPublicId) {
        GitHubSubscriptionDeletionTarget target = deletionStateService.begin(
                subscriptionPublicId
        );
        if (target.registrationMode() == GitHubRegistrationMode.MANUAL
                && target.webhookId() != null) {
            deletionStateService.fail(
                    target.subscriptionId(),
                    GitHubSubscriptionStatus.ORPHANED,
                    "The manually registered GitHub webhook was retained; "
                            + "delete Hook " + target.webhookId() + " in GitHub"
            );
            throw new GitHubIntegrationException(
                    HttpStatus.CONFLICT,
                    "GITHUB_MANUAL_WEBHOOK_DELETE_REQUIRED",
                    "The Pipeline triggers are disabled, but the manually registered GitHub "
                            + "webhook must be deleted in GitHub"
            );
        }
        if (target.webhookId() == null) {
            deletionStateService.complete(target.subscriptionId());
            return;
        }
        try {
            GitHubConnectionEntity connection = activeConnection(target.connectionId());
            apiClient.deleteWebhook(
                    token(connection),
                    target.owner(),
                    target.repositoryName(),
                    target.webhookId()
            );
        } catch (RuntimeException exception) {
            deletionStateService.fail(
                    target.subscriptionId(),
                    GitHubSubscriptionStatus.DELETING,
                    exception.getMessage()
            );
            throw exception;
        }
        deletionStateService.complete(target.subscriptionId());
    }

    public GitHubRepositorySubscriptionEntity subscription(String publicId) {
        return subscriptionRepository.findByPublicId(publicId)
                .orElseThrow(() -> new GitHubIntegrationException(
                        HttpStatus.NOT_FOUND,
                        "GITHUB_SUBSCRIPTION_NOT_FOUND",
                        "GitHub repository subscription was not found"
                ));
    }

    public String webhookSecret(GitHubRepositorySubscriptionEntity subscription) {
        return secretCipher.decrypt(
                subscription.getWebhookSecretCiphertext(),
                subscription.getWebhookSecretNonce(),
                subscription.getWebhookSecretKeyVersion()
        );
    }

    public List<String> readEvents(String events) {
        try {
            return objectMapper.readValue(events, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored GitHub events are invalid", exception);
        }
    }

    private List<GitHubWebhook> matchingCallbacks(
            GitHubConnectionEntity connection,
            GitHubRepositorySubscriptionEntity subscription
    ) {
        URI callback = properties.getWebhookCallbackUrl();
        return apiClient.listWebhooks(
                        token(connection),
                        subscription.getOwner(),
                        subscription.getRepositoryName()
                ).stream()
                .filter(hook -> hook.config() != null
                        && callback.toString().equals(hook.config().url()))
                .toList();
    }

    private void updateRepository(
            GitHubRepositorySubscriptionEntity subscription,
            GitHubRepository repository,
            String owner,
            String repositoryName,
            List<String> events
    ) {
        subscription.setNodeId(repository.nodeId())
                .setOwner(owner)
                .setRepositoryName(repositoryName)
                .setFullName(repository.fullName())
                .setHtmlUrl(repository.htmlUrl())
                .setCloneUrl(repository.cloneUrl())
                .setDefaultBranch(repository.defaultBranch())
                .setEvents(writeEvents(events))
                .setUpdatedAt(now());
    }

    private GitHubConnectionEntity activeConnection(String publicId) {
        return connectionRepository.findByPublicId(publicId)
                .filter(connection -> connection.getStatus() == GitHubConnectionStatus.ACTIVE)
                .orElseThrow(() -> new GitHubIntegrationException(
                        HttpStatus.NOT_FOUND,
                        "GITHUB_CONNECTION_NOT_FOUND",
                        "Active GitHub connection was not found"
                ));
    }

    private GitHubConnectionEntity activeConnection(Long id) {
        return connectionRepository.findById(id)
                .filter(connection -> connection.getStatus() == GitHubConnectionStatus.ACTIVE)
                .orElseThrow(() -> new GitHubIntegrationException(
                        HttpStatus.CONFLICT,
                        "GITHUB_CONNECTION_NOT_ACTIVE",
                        "GitHub connection is not active"
                ));
    }

    private String token(GitHubConnectionEntity connection) {
        return secretCipher.decrypt(
                connection.getAccessTokenCiphertext(),
                connection.getTokenNonce(),
                connection.getEncryptionKeyVersion()
        );
    }

    private List<String> normalizeEvents(List<String> requested) {
        LinkedHashSet<String> events = new LinkedHashSet<>(
                requested == null || requested.isEmpty()
                        ? List.of("push", "pull_request")
                        : requested
        );
        if (events.isEmpty() || !SUPPORTED_EVENTS.containsAll(events)) {
            throw new GitHubIntegrationException(
                    HttpStatus.BAD_REQUEST,
                    "GITHUB_WEBHOOK_EVENTS_INVALID",
                    "Only push and pull_request webhook events are supported"
            );
        }
        return List.copyOf(events);
    }

    private String writeEvents(List<String> events) {
        try {
            return objectMapper.writeValueAsString(events);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize GitHub events", exception);
        }
    }

    private String randomSecret() {
        byte[] value = new byte[32];
        secureRandom.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private GitHubSubscriptionResponse response(
            GitHubRepositorySubscriptionEntity subscription,
            String plaintextSecret
    ) {
        return new GitHubSubscriptionResponse(
                subscription.getPublicId(),
                subscription.getGithubRepositoryId(),
                subscription.getFullName(),
                subscription.getWebhookId(),
                subscription.getRegistrationMode(),
                subscription.getStatus(),
                properties.getWebhookCallbackUrl(),
                plaintextSecret,
                readEvents(subscription.getEvents())
        );
    }

    private GitHubIntegrationException conflict(String message) {
        return new GitHubIntegrationException(
                HttpStatus.CONFLICT,
                "GITHUB_SUBSCRIPTION_CONFLICT",
                message
        );
    }
}
