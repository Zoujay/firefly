package firefly.github.service;

import firefly.github.dao.GitHubWebhookDeliveryRepository;
import firefly.github.model.GitHubDeliveryStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
public class GitHubDeliveryRecoveryScheduler {

    private final GitHubWebhookDeliveryRepository deliveryRepository;
    private final GitHubDeliveryStateService stateService;
    private final GitHubWebhookProcessingService processingService;
    private final Clock clock;

    public GitHubDeliveryRecoveryScheduler(
            GitHubWebhookDeliveryRepository deliveryRepository,
            GitHubDeliveryStateService stateService,
            GitHubWebhookProcessingService processingService,
            Clock clock
    ) {
        this.deliveryRepository = deliveryRepository;
        this.stateService = stateService;
        this.processingService = processingService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${firefly.github.processing.recovery-interval:60s}")
    public void recoverAndRetry() {
        stateService.recoverExpired();
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        deliveryRepository
                .findTop100ByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
                        GitHubDeliveryStatus.RETRYABLE,
                        now
                )
                .forEach(delivery -> {
                    try {
                        processingService.process(delivery.getDeliveryId());
                    } catch (RuntimeException ignored) {
                        // The processing service persists the retry state and error summary.
                    }
                });
    }
}
