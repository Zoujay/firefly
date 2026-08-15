package firefly.github.service;

import firefly.bean.dto.PipelineBuildDto;
import firefly.bean.dto.message.GithubMessageEntity;
import firefly.constant.BuildStatus;
import firefly.constant.TriggerOrigin;
import firefly.github.dao.GitHubDeliveryPipelineRepository;
import firefly.github.model.GitHubDeliveryPipelineEntity;
import firefly.github.model.GitHubDeliveryPipelineStatus;
import firefly.github.webhook.GitHubWebhookEvent;
import firefly.model.pipeline.PipelineModel;
import firefly.service.pipelinebuild.IPipelineBuildService;
import firefly.service.trigger.TriggerCenter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class GitHubPipelineInvocationService {

    private final GitHubDeliveryPipelineRepository deliveryPipelineRepository;
    private final IPipelineBuildService pipelineBuildService;
    private final TriggerCenter triggerCenter;
    private final Clock clock;

    public GitHubPipelineInvocationService(
            GitHubDeliveryPipelineRepository deliveryPipelineRepository,
            IPipelineBuildService pipelineBuildService,
            TriggerCenter triggerCenter,
            Clock clock
    ) {
        this.deliveryPipelineRepository = deliveryPipelineRepository;
        this.pipelineBuildService = pipelineBuildService;
        this.triggerCenter = triggerCenter;
        this.clock = clock;
    }

    @Transactional
    public void invoke(GitHubWebhookEvent event, PipelineModel pipeline) {
        GitHubDeliveryPipelineEntity deliveryPipeline = deliveryPipelineRepository
                .findByDeliveryIdAndPipelineId(event.deliveryId(), pipeline.getId())
                .orElse(null);
        if (deliveryPipeline != null
                && deliveryPipeline.getStatus() == GitHubDeliveryPipelineStatus.SUCCESS) {
            return;
        }
        LocalDateTime now = now();
        if (deliveryPipeline == null) {
            deliveryPipeline = new GitHubDeliveryPipelineEntity()
                    .setDeliveryId(event.deliveryId())
                    .setPipelineId(pipeline.getId())
                    .setProcessingAttempt(1)
                    .setCreatedAt(now);
        } else {
            deliveryPipeline.setProcessingAttempt(deliveryPipeline.getProcessingAttempt() + 1);
        }
        deliveryPipeline.setStatus(GitHubDeliveryPipelineStatus.PROCESSING)
                .setLastError("")
                .setUpdatedAt(now);
        deliveryPipelineRepository.saveAndFlush(deliveryPipeline);

        PipelineBuildDto build = new PipelineBuildDto()
                .setPipelineID(pipeline.getId())
                .setPipelineUUID(pipeline.getPipelineUUID())
                .setTriggerOrigin(TriggerOrigin.GITHUB)
                .setExecutionAttempt(0)
                .setBuildStatus(BuildStatus.PENDING);
        Long pipelineBuildId = pipelineBuildService.buildPipeline(build);
        if (pipelineBuildId == null || pipelineBuildId <= 0) {
            throw new IllegalStateException("Failed to create GitHub pipeline build");
        }

        GithubMessageEntity message = new GithubMessageEntity();
        message.setDeliveryId(event.deliveryId())
                .setEventType(event.eventType())
                .setAction(event.action())
                .setRepositoryId(event.repositoryId())
                .setRepositoryFullName(event.repositoryFullName())
                .setRepositoryUrl(event.repositoryUrl())
                .setCloneUrl(event.cloneUrl())
                .setSourceBranch(event.sourceBranch())
                .setTargetBranch(event.targetBranch())
                .setMatchBranch(event.matchBranch())
                .setHeadSha(event.headSha())
                .setSenderId(event.senderId())
                .setSenderLogin(event.senderLogin())
                .setReceivedAt(event.receivedAt());
        message.setPipelineID(pipeline.getId())
                .setPipelineBuildID(pipelineBuildId)
                .setExecutionAttempt(0)
                .setTriggerOrigin(TriggerOrigin.GITHUB);
        triggerCenter.dispatch(message);

        deliveryPipeline.setPipelineBuildId(pipelineBuildId)
                .setStatus(GitHubDeliveryPipelineStatus.SUCCESS)
                .setUpdatedAt(now());
        deliveryPipelineRepository.save(deliveryPipeline);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
