package firefly.github;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import firefly.constant.TriggerMatch;
import firefly.constant.TriggerModel;
import firefly.constant.TriggerOrigin;
import firefly.github.model.GitHubTriggerConfigEntity;
import firefly.github.service.GitHubTriggerMatcher;
import firefly.github.webhook.GitHubWebhookEvent;
import firefly.model.pipeline.PipelineModel;
import firefly.service.triggerorigin.impl.GithubTriggerOriginServiceImpl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

@ExtendWith(MockitoExtension.class)
class GitHubTriggerMatcherTests {

    @Mock
    private GithubTriggerOriginServiceImpl configService;

    @Test
    void matchesPrefixBranchAndPullRequestAction() {
        when(configService.read("events")).thenReturn(List.of("pull_request"));
        when(configService.read("actions")).thenReturn(List.of("synchronize"));
        GitHubTriggerMatcher matcher = new GitHubTriggerMatcher(configService);
        GitHubTriggerConfigEntity config =
            new GitHubTriggerConfigEntity()
                .setPipelineId(10L)
                .setEnabled(true)
                .setEvents("events")
                .setPullRequestActions("actions")
                .setIgnoreDeletePush(true);
        PipelineModel pipeline =
            new PipelineModel()
                .setId(10L)
                .setTriggerOrigin(TriggerOrigin.GITHUB)
                .setTriggerMode(TriggerModel.AUTOMATIC)
                .setTriggerMatch(TriggerMatch.PREFIX)
                .setBranchPattern("release/");
        GitHubWebhookEvent event = event("pull_request", "synchronize", "release/1.0", false);

        assertTrue(matcher.matches(event, config, pipeline));
    }

    @Test
    void rejectsManualPipelineAndDeletedPush() {
        when(configService.read("events")).thenReturn(List.of("push"));
        GitHubTriggerMatcher matcher = new GitHubTriggerMatcher(configService);
        GitHubTriggerConfigEntity config =
            new GitHubTriggerConfigEntity()
                .setPipelineId(10L)
                .setEnabled(true)
                .setEvents("events")
                .setPullRequestActions("actions")
                .setIgnoreDeletePush(true);
        PipelineModel pipeline =
            new PipelineModel()
                .setId(10L)
                .setTriggerOrigin(TriggerOrigin.GITHUB)
                .setTriggerMode(TriggerModel.AUTOMATIC)
                .setTriggerMatch(TriggerMatch.ACCURATE)
                .setBranchPattern("main");

        assertFalse(matcher.matches(event("push", null, "main", true), config, pipeline));
        pipeline.setTriggerMode(TriggerModel.MANUAL);
        assertFalse(matcher.matches(event("push", null, "main", false), config, pipeline));
    }

    private GitHubWebhookEvent event(
        String eventType, String action, String branch, boolean deleted) {
        return new GitHubWebhookEvent(
            "11111111-1111-1111-1111-111111111111",
            eventType,
            action,
            1L,
            "acme/repo",
            "https://github.com/acme/repo",
            "https://github.com/acme/repo.git",
            2L,
            null,
            branch,
            branch,
            branch,
            "abc",
            3L,
            "octocat",
            Instant.parse("2026-08-15T00:00:00Z"),
            deleted,
            null);
    }
}
