package firefly.github.webhook;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record GitHubWebhookEvent(
        String deliveryId,
        String eventType,
        String action,
        Long repositoryId,
        String repositoryFullName,
        String repositoryUrl,
        String cloneUrl,
        Long hookId,
        String ref,
        String sourceBranch,
        String targetBranch,
        String matchBranch,
        String headSha,
        Long senderId,
        String senderLogin,
        Instant receivedAt,
        boolean deleted,
        JsonNode payload
) {
    public boolean ping() {
        return "ping".equals(eventType);
    }

    public GitHubWebhookEvent withReceivedAt(Instant value) {
        return new GitHubWebhookEvent(
                deliveryId, eventType, action, repositoryId, repositoryFullName,
                repositoryUrl, cloneUrl, hookId, ref, sourceBranch, targetBranch,
                matchBranch, headSha, senderId, senderLogin, value, deleted, payload
        );
    }
}
