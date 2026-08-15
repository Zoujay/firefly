package firefly.github;

import firefly.github.config.GitHubProcessingProperties;
import firefly.github.dao.GitHubWebhookDeliveryRepository;
import firefly.github.model.GitHubDeliveryStatus;
import firefly.github.model.GitHubWebhookDeliveryEntity;
import firefly.github.service.GitHubDeliveryStateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitHubDeliveryStateServiceTests {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-16T00:00:00Z"),
            ZoneOffset.UTC
    );

    @Mock
    private GitHubWebhookDeliveryRepository deliveryRepository;

    @Test
    void staleProcessorCannotFinishDelivery() {
        GitHubWebhookDeliveryEntity delivery = new GitHubWebhookDeliveryEntity()
                .setDeliveryId("delivery")
                .setProcessingAttempt(1);
        when(deliveryRepository.findByDeliveryId("delivery"))
                .thenReturn(Optional.of(delivery));
        when(deliveryRepository.finishOwned(
                eq("delivery"),
                eq("old-worker"),
                eq(GitHubDeliveryStatus.PROCESSING),
                eq(GitHubDeliveryStatus.SUCCESS),
                eq(""),
                any(LocalDateTime.class),
                eq(null)
        )).thenReturn(0);

        boolean finished = service().finish(
                "delivery",
                "old-worker",
                GitHubDeliveryStatus.SUCCESS,
                ""
        );

        assertFalse(finished);
    }

    @Test
    void ownedProcessorCanFinishDelivery() {
        GitHubWebhookDeliveryEntity delivery = new GitHubWebhookDeliveryEntity()
                .setDeliveryId("delivery")
                .setProcessingAttempt(1);
        when(deliveryRepository.findByDeliveryId("delivery"))
                .thenReturn(Optional.of(delivery));
        when(deliveryRepository.finishOwned(
                eq("delivery"),
                eq("worker"),
                eq(GitHubDeliveryStatus.PROCESSING),
                eq(GitHubDeliveryStatus.SUCCESS),
                eq(""),
                any(LocalDateTime.class),
                eq(null)
        )).thenReturn(1);

        assertTrue(service().finish(
                "delivery",
                "worker",
                GitHubDeliveryStatus.SUCCESS,
                ""
        ));
        verify(deliveryRepository).finishOwned(
                eq("delivery"),
                eq("worker"),
                eq(GitHubDeliveryStatus.PROCESSING),
                eq(GitHubDeliveryStatus.SUCCESS),
                eq(""),
                any(LocalDateTime.class),
                eq(null)
        );
    }

    private GitHubDeliveryStateService service() {
        GitHubProcessingProperties properties = new GitHubProcessingProperties();
        properties.setMaxAttempts(5);
        return new GitHubDeliveryStateService(deliveryRepository, properties, CLOCK);
    }
}
