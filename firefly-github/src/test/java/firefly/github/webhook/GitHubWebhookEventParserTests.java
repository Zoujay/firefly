package firefly.github.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GitHubWebhookEventParserTests {

  private final GitHubWebhookEventParser parser = new GitHubWebhookEventParser(new ObjectMapper());

  @Test
  void normalizesPushHeadBranch() {
    GitHubWebhookEvent event =
        parser.parse(
            "11111111-1111-1111-1111-111111111111",
            "push",
            """
            {
              "ref":"refs/heads/release/1.0",
              "after":"abc123",
              "deleted":false,
              "repository":{"id":42,"full_name":"acme/repo","html_url":"https://github.com/acme/repo","clone_url":"https://github.com/acme/repo.git"},
              "sender":{"id":7,"login":"octocat"}
            }
            """
                .getBytes(StandardCharsets.UTF_8));

    assertEquals("release/1.0", event.matchBranch());
    assertEquals("release/1.0", event.sourceBranch());
    assertEquals("abc123", event.headSha());
    assertEquals(42L, event.repositoryId());
  }

  @Test
  void ignoresTagPushForBranchMatchingAndUsesPullRequestBaseBranch() {
    GitHubWebhookEvent tag =
        parser.parse(
            "22222222-2222-2222-2222-222222222222",
            "push",
            """
            {"ref":"refs/tags/v1","after":"abc","repository":{"id":42}}
            """
                .getBytes(StandardCharsets.UTF_8));
    assertNull(tag.matchBranch());

    GitHubWebhookEvent pullRequest =
        parser.parse(
            "33333333-3333-3333-3333-333333333333",
            "pull_request",
            """
            {
              "action":"synchronize",
              "repository":{"id":42},
              "pull_request":{"head":{"ref":"feature/a","sha":"def456"},"base":{"ref":"main"}}
            }
            """
                .getBytes(StandardCharsets.UTF_8));
    assertEquals("feature/a", pullRequest.sourceBranch());
    assertEquals("main", pullRequest.targetBranch());
    assertEquals("main", pullRequest.matchBranch());
    assertEquals("def456", pullRequest.headSha());
  }
}
