package firefly.github.service;

import firefly.dao.pipelineconfig.IPipelineConfigDao;
import firefly.github.dao.GitHubTriggerConfigRepository;
import firefly.github.dao.GitHubWebhookDeliveryRepository;
import firefly.github.model.GitHubDeliveryStatus;
import firefly.github.model.GitHubTriggerConfigEntity;
import firefly.github.model.GitHubWebhookDeliveryEntity;
import firefly.github.webhook.GitHubWebhookEvent;
import firefly.github.webhook.GitHubWebhookEventParser;
import firefly.model.pipeline.PipelineModel;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class GitHubWebhookProcessingService {

    private final GitHubWebhookDeliveryRepository deliveryRepository;
    private final GitHubTriggerConfigRepository triggerConfigRepository;
    private final IPipelineConfigDao pipelineConfigDao;
    private final GitHubWebhookEventParser eventParser;
    private final GitHubTriggerMatcher triggerMatcher;
    private final GitHubPipelineInvocationService invocationService;
    private final GitHubDeliveryStateService stateService;

    public GitHubWebhookProcessingService(
        GitHubWebhookDeliveryRepository deliveryRepository,
        GitHubTriggerConfigRepository triggerConfigRepository,
        IPipelineConfigDao pipelineConfigDao,
        GitHubWebhookEventParser eventParser,
        GitHubTriggerMatcher triggerMatcher,
        GitHubPipelineInvocationService invocationService,
        GitHubDeliveryStateService stateService) {
        this.deliveryRepository = deliveryRepository;
        this.triggerConfigRepository = triggerConfigRepository;
        this.pipelineConfigDao = pipelineConfigDao;
        this.eventParser = eventParser;
        this.triggerMatcher = triggerMatcher;
        this.invocationService = invocationService;
        this.stateService = stateService;
    }

    public void process(String deliveryId) {
        String processorId = UUID.randomUUID().toString();
        if (!stateService.claim(deliveryId, processorId)) {
            return;
        }
        try {
            GitHubWebhookDeliveryEntity delivery =
                deliveryRepository
                    .findByDeliveryId(deliveryId)
                    .orElseThrow(
                        () ->
                            new IllegalStateException(
                                "GitHub delivery was not found: "
                                    + deliveryId));
            GitHubWebhookEvent event =
                eventParser
                    .parse(
                        delivery.getDeliveryId(),
                        delivery.getEventType(),
                        delivery.getPayload().getBytes(StandardCharsets.UTF_8))
                    .withReceivedAt(delivery.getReceivedAt().toInstant(ZoneOffset.UTC));
            List<GitHubTriggerConfigEntity> configs =
                triggerConfigRepository.findAllBySubscriptionIdAndEnabledTrue(
                    delivery.getSubscriptionId());
            Set<Long> pipelineIds = new LinkedHashSet<>();
            configs.forEach(config -> pipelineIds.add(config.getPipelineId()));
            Map<Long, PipelineModel> pipelines = new HashMap<>();
            pipelineConfigDao
                .findAllById(pipelineIds)
                .forEach(pipeline -> pipelines.put(pipeline.getId(), pipeline));

            int matches = 0;
            for (GitHubTriggerConfigEntity config : configs) {
                PipelineModel pipeline = pipelines.get(config.getPipelineId());
                if (pipeline == null || !triggerMatcher.matches(event, config, pipeline)) {
                    continue;
                }
                invocationService.invoke(event, pipeline);
                matches++;
            }
            stateService.finish(
                deliveryId,
                processorId,
                matches == 0 ? GitHubDeliveryStatus.IGNORED : GitHubDeliveryStatus.SUCCESS,
                "");
        } catch (Exception exception) {
            stateService.finish(
                deliveryId,
                processorId,
                GitHubDeliveryStatus.RETRYABLE,
                exception.getMessage());
            throw exception;
        }
    }
}
