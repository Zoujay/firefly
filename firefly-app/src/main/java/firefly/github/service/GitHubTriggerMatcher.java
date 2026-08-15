package firefly.github.service;

import firefly.constant.TriggerMatch;
import firefly.constant.TriggerModel;
import firefly.constant.TriggerOrigin;
import firefly.github.model.GitHubTriggerConfigEntity;
import firefly.github.webhook.GitHubWebhookEvent;
import firefly.model.pipeline.PipelineModel;
import firefly.service.triggerorigin.impl.GithubTriggerOriginServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GitHubTriggerMatcher {

    private final GithubTriggerOriginServiceImpl triggerConfigService;

    public GitHubTriggerMatcher(GithubTriggerOriginServiceImpl triggerConfigService) {
        this.triggerConfigService = triggerConfigService;
    }

    public boolean matches(
            GitHubWebhookEvent event,
            GitHubTriggerConfigEntity config,
            PipelineModel pipeline
    ) {
        if (pipeline.getTriggerOrigin() != TriggerOrigin.GITHUB
                || pipeline.getTriggerMode() != TriggerModel.AUTOMATIC
                || !Boolean.TRUE.equals(config.getEnabled())
                || !config.getPipelineId().equals(pipeline.getId())) {
            return false;
        }
        List<String> events = triggerConfigService.read(config.getEvents());
        if (!events.contains(event.eventType())) {
            return false;
        }
        if ("push".equals(event.eventType())
                && event.deleted()
                && Boolean.TRUE.equals(config.getIgnoreDeletePush())) {
            return false;
        }
        if ("pull_request".equals(event.eventType())) {
            List<String> actions = triggerConfigService.read(config.getPullRequestActions());
            if (event.action() == null || !actions.contains(event.action())) {
                return false;
            }
        }
        if (event.matchBranch() == null
                || pipeline.getBranchPattern() == null
                || pipeline.getBranchPattern().isBlank()) {
            return false;
        }
        if (pipeline.getTriggerMatch() == TriggerMatch.ACCURATE) {
            return event.matchBranch().equals(pipeline.getBranchPattern());
        }
        if (pipeline.getTriggerMatch() == TriggerMatch.PREFIX) {
            return event.matchBranch().startsWith(pipeline.getBranchPattern());
        }
        return false;
    }
}
