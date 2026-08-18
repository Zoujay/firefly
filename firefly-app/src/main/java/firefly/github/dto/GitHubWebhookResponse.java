package firefly.github.dto;

public record GitHubWebhookResponse(String status, String deliveryId, String event) {}
