package firefly.github.controller;

import firefly.github.dto.GitHubWebhookResponse;
import firefly.github.http.GitHubIntegrationException;
import firefly.github.service.GitHubWebhookIngressService;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/github")
public class GitHubWebhookController {

    private static final int MAX_PAYLOAD_BYTES = 2 * 1024 * 1024;
    private final GitHubWebhookIngressService ingressService;

    public GitHubWebhookController(GitHubWebhookIngressService ingressService) {
        this.ingressService = ingressService;
    }

    @PostMapping(value = "/webhooks", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GitHubWebhookResponse> receive(
        @RequestHeader("X-GitHub-Delivery") String deliveryId,
        @RequestHeader("X-GitHub-Event") String eventType,
        @RequestHeader("X-GitHub-Hook-ID") String hookId,
        @RequestHeader("X-GitHub-Hook-Installation-Target-Type") String targetType,
        @RequestHeader("X-GitHub-Hook-Installation-Target-ID") String targetId,
        @RequestHeader("X-Hub-Signature-256") String signature,
        @RequestBody byte[] rawPayload) {
        if (!StringUtils.hasText(deliveryId)
            || !StringUtils.hasText(eventType)
            || rawPayload == null
            || rawPayload.length == 0) {
            throw badRequest("Required GitHub webhook data is missing");
        }
        if (rawPayload.length > MAX_PAYLOAD_BYTES) {
            throw new GitHubIntegrationException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "GITHUB_WEBHOOK_PAYLOAD_TOO_LARGE",
                "GitHub webhook payload exceeds 2 MiB");
        }
        try {
            GitHubWebhookResponse response =
                ingressService.receive(
                    deliveryId,
                    eventType,
                    Long.valueOf(hookId),
                    targetType,
                    Long.valueOf(targetId),
                    signature,
                    rawPayload);
            return ResponseEntity.accepted().body(response);
        } catch (NumberFormatException exception) {
            throw badRequest("GitHub Hook or target ID is invalid");
        }
    }

    private GitHubIntegrationException badRequest(String message) {
        return new GitHubIntegrationException(
            HttpStatus.BAD_REQUEST, "GITHUB_WEBHOOK_REQUEST_INVALID", message);
    }
}
