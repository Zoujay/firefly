package firefly.github.controller;

import firefly.github.dao.GitHubWebhookDeliveryRepository;
import firefly.github.dto.GitHubDeliveryResponse;
import firefly.github.http.GitHubIntegrationException;
import firefly.github.model.GitHubWebhookDeliveryEntity;
import firefly.github.service.GitHubDeliveryStateService;
import firefly.github.service.GitHubWebhookProcessingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/github/deliveries")
public class GitHubDeliveryController {

    private final GitHubWebhookDeliveryRepository deliveryRepository;
    private final GitHubDeliveryStateService stateService;
    private final GitHubWebhookProcessingService processingService;

    public GitHubDeliveryController(
            GitHubWebhookDeliveryRepository deliveryRepository,
            GitHubDeliveryStateService stateService,
            GitHubWebhookProcessingService processingService
    ) {
        this.deliveryRepository = deliveryRepository;
        this.stateService = stateService;
        this.processingService = processingService;
    }

    @GetMapping("/{deliveryId}")
    public GitHubDeliveryResponse get(@PathVariable String deliveryId) {
        GitHubWebhookDeliveryEntity delivery = deliveryRepository.findByDeliveryId(deliveryId)
                .orElseThrow(() -> new GitHubIntegrationException(
                        HttpStatus.NOT_FOUND,
                        "GITHUB_DELIVERY_NOT_FOUND",
                        "GitHub delivery was not found"
                ));
        return new GitHubDeliveryResponse(
                delivery.getDeliveryId(),
                delivery.getEventType(),
                delivery.getRepositoryId(),
                delivery.getStatus(),
                delivery.getProcessingAttempt(),
                delivery.getLastError(),
                delivery.getReceivedAt(),
                delivery.getProcessingFinishedAt()
        );
    }

    @PostMapping("/{deliveryId}/retry")
    public ResponseEntity<Void> retry(@PathVariable String deliveryId) {
        stateService.requestRetry(deliveryId);
        processingService.process(deliveryId);
        return ResponseEntity.accepted().build();
    }
}
