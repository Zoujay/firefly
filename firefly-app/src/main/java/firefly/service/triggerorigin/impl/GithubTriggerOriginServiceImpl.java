package firefly.service.triggerorigin.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import firefly.bean.dto.BaseTriggerOriginDto;
import firefly.bean.dto.GithubTriggerConfigDto;
import firefly.bean.dto.PipelineBuildDto;
import firefly.bean.dto.message.BaseMessage;
import firefly.bean.dto.message.GithubMessageEntity;
import firefly.constant.TriggerOrigin;
import firefly.github.dao.GitHubRepositorySubscriptionRepository;
import firefly.github.dao.GitHubTriggerConfigRepository;
import firefly.github.http.GitHubIntegrationException;
import firefly.github.model.GitHubRepositorySubscriptionEntity;
import firefly.github.model.GitHubSubscriptionStatus;
import firefly.github.model.GitHubTriggerConfigEntity;
import firefly.service.triggerorigin.ITriggerOrigin;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@Transactional
public class GithubTriggerOriginServiceImpl implements ITriggerOrigin {

    private static final Set<String> EVENTS = Set.of("push", "pull_request");
    private static final List<String> DEFAULT_PR_ACTIONS =
            List.of("opened", "reopened", "synchronize", "ready_for_review");
    private final ObjectMapper objectMapper;
    private final GitHubTriggerConfigRepository triggerConfigRepository;
    private final GitHubRepositorySubscriptionRepository subscriptionRepository;
    private final Clock clock;

    public GithubTriggerOriginServiceImpl(
            ObjectMapper objectMapper,
            GitHubTriggerConfigRepository triggerConfigRepository,
            GitHubRepositorySubscriptionRepository subscriptionRepository,
            Clock clock) {
        this.objectMapper = objectMapper;
        this.triggerConfigRepository = triggerConfigRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.clock = clock;
    }

    @Override
    public TriggerOrigin getTriggerOrigin() {
        return TriggerOrigin.GITHUB;
    }

    @Override
    public BaseTriggerOriginDto parseTriggerOrigin(JsonNode triggerOrigin) {
        return objectMapper.convertValue(triggerOrigin, GithubTriggerConfigDto.class);
    }

    @Override
    public BaseMessage buildMessage(PipelineBuildDto pipelineBuildDto, Long pipelineBuildID) {
        GitHubTriggerConfigEntity config =
                triggerConfigRepository
                        .findByPipelineId(pipelineBuildDto.getPipelineID())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "GitHub trigger config was not found for pipeline "
                                                        + pipelineBuildDto.getPipelineID()));
        GitHubRepositorySubscriptionEntity subscription =
                subscriptionRepository
                        .findById(config.getSubscriptionId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "GitHub subscription was not found for trigger"
                                                        + " config "
                                                        + config.getId()));
        GithubMessageEntity message = new GithubMessageEntity();
        message.setRepositoryUrl(subscription.getHtmlUrl())
                .setRepositoryId(subscription.getGithubRepositoryId())
                .setPipelineID(pipelineBuildDto.getPipelineID())
                .setPipelineBuildID(pipelineBuildID)
                .setExecutionAttempt(pipelineBuildDto.getExecutionAttempt())
                .setTriggerOrigin(TriggerOrigin.GITHUB);
        return message;
    }

    @Override
    public Long saveTriggerOrigin(JsonNode triggerOrigin, Long pipelineID) {
        GithubTriggerConfigDto dto = (GithubTriggerConfigDto) parseTriggerOrigin(triggerOrigin);
        if (dto.getSubscriptionId() == null || dto.getSubscriptionId().isBlank()) {
            throw new IllegalArgumentException("GitHub subscriptionId is required");
        }
        GitHubRepositorySubscriptionEntity subscription =
                subscriptionRepository
                        .findByPublicId(dto.getSubscriptionId())
                        .filter(item -> item.getStatus() == GitHubSubscriptionStatus.ACTIVE)
                        .orElseThrow(
                                () ->
                                        new GitHubIntegrationException(
                                                HttpStatus.BAD_REQUEST,
                                                "GITHUB_SUBSCRIPTION_NOT_ACTIVE",
                                                "GitHub subscription must be active"));
        List<String> events = normalizeEvents(dto.getEvents());
        List<String> actions =
                dto.getPullRequestActions() == null
                        ? DEFAULT_PR_ACTIONS
                        : List.copyOf(new LinkedHashSet<>(dto.getPullRequestActions()));
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        GitHubTriggerConfigEntity entity =
                new GitHubTriggerConfigEntity()
                        .setPipelineId(pipelineID)
                        .setSubscriptionId(subscription.getId())
                        .setEnabled(true)
                        .setDisabledReason("")
                        .setEvents(write(events))
                        .setPullRequestActions(write(actions))
                        .setIgnoreDeletePush(!Boolean.FALSE.equals(dto.getIgnoreDeletePush()))
                        .setCreatedAt(now)
                        .setUpdatedAt(now);
        return triggerConfigRepository.saveAndFlush(entity).getId();
    }

    public List<String> read(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored GitHub trigger config is invalid", exception);
        }
    }

    private List<String> normalizeEvents(List<String> requested) {
        LinkedHashSet<String> events =
                new LinkedHashSet<>(
                        requested == null || requested.isEmpty()
                                ? List.of("push", "pull_request")
                                : requested);
        if (!EVENTS.containsAll(events)) {
            throw new IllegalArgumentException("Only push and pull_request events are supported");
        }
        return List.copyOf(events);
    }

    private String write(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize GitHub trigger config", exception);
        }
    }
}
