package firefly.github.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import firefly.github.http.GitHubIntegrationException;

import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

public class GitHubWebhookEventParser {

    private final ObjectMapper objectMapper;

    public GitHubWebhookEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public GitHubWebhookEvent parse(String deliveryId, String eventType, byte[] rawPayload) {
        if (!StringUtils.hasText(deliveryId)
            || !StringUtils.hasText(eventType)
            || rawPayload == null
            || rawPayload.length == 0) {
            throw invalidPayload("GitHub delivery, event and payload are required", null);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(new String(rawPayload, StandardCharsets.UTF_8));
        } catch (JsonProcessingException exception) {
            throw invalidPayload("GitHub webhook payload is not valid JSON", exception);
        }
        if (root == null || !root.isObject()) {
            throw invalidPayload("GitHub webhook payload must be a JSON object", null);
        }

        JsonNode repository = root.path("repository");
        Long repositoryId = nullableLong(repository.path("id"));
        String fullName = text(repository.path("full_name"));
        String repositoryUrl = text(repository.path("html_url"));
        String cloneUrl = text(repository.path("clone_url"));
        String action = text(root.path("action"));
        Long hookId = nullableLong(root.path("hook").path("hook_id"));
        JsonNode sender = root.path("sender");

        String ref = null;
        String sourceBranch = null;
        String targetBranch = null;
        String matchBranch = null;
        String headSha = null;
        boolean deleted = false;

        if ("push".equals(eventType)) {
            ref = text(root.path("ref"));
            deleted = root.path("deleted").asBoolean(false);
            headSha = text(root.path("after"));
            if (ref != null && ref.startsWith("refs/heads/")) {
                sourceBranch = ref.substring("refs/heads/".length());
                matchBranch = sourceBranch;
            }
        } else if ("pull_request".equals(eventType)) {
            JsonNode pullRequest = root.path("pull_request");
            sourceBranch = text(pullRequest.path("head").path("ref"));
            targetBranch = text(pullRequest.path("base").path("ref"));
            matchBranch = targetBranch;
            headSha = text(pullRequest.path("head").path("sha"));
        } else if (!"ping".equals(eventType)) {
            throw new GitHubIntegrationException(
                HttpStatus.ACCEPTED,
                "GITHUB_WEBHOOK_EVENT_UNSUPPORTED",
                "GitHub webhook event is not supported: " + eventType);
        }

        return new GitHubWebhookEvent(
            deliveryId,
            eventType,
            action,
            repositoryId,
            fullName,
            repositoryUrl,
            cloneUrl,
            hookId,
            ref,
            sourceBranch,
            targetBranch,
            matchBranch,
            headSha,
            nullableLong(sender.path("id")),
            text(sender.path("login")),
            Instant.now(),
            deleted,
            root);
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText();
        return StringUtils.hasText(value) ? value : null;
    }

    private Long nullableLong(JsonNode node) {
        return node != null && node.canConvertToLong() ? node.longValue() : null;
    }

    private GitHubIntegrationException invalidPayload(String message, Throwable cause) {
        return new GitHubIntegrationException(
            HttpStatus.BAD_REQUEST, "GITHUB_WEBHOOK_PAYLOAD_INVALID", message, cause);
    }
}
