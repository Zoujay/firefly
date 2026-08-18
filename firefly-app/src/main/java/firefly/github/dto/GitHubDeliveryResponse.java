package firefly.github.dto;

import firefly.github.model.GitHubDeliveryStatus;

import java.time.LocalDateTime;

public record GitHubDeliveryResponse(
        String deliveryId,
        String eventType,
        Long repositoryId,
        GitHubDeliveryStatus status,
        Integer processingAttempt,
        String lastError,
        LocalDateTime receivedAt,
        LocalDateTime processingFinishedAt) {}
