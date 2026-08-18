package firefly.github.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import firefly.github.config.GitHubProperties;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GitHubOAuthClientTests {

  @Test
  void buildsAuthorizationUriWithStatePkceAndScopes() {
    GitHubProperties properties = new GitHubProperties();
    properties.setClientId("client-id");
    properties.setClientSecret("client-secret");
    properties.setRedirectUri(URI.create("https://firefly.example/api/github/oauth/callback"));
    properties.setScopes(List.of("admin:repo_hook", "repo"));
    GitHubOAuthClient client = new GitHubOAuthClient(properties, RestClient.builder());

    URI result = client.createAuthorizationUri("state-value", "challenge-value");

    assertEquals("https", result.getScheme());
    assertEquals("github.com", result.getHost());
    assertEquals("/login/oauth/authorize", result.getPath());
    assertTrue(result.getRawQuery().contains("client_id=client-id"));
    assertTrue(result.getRawQuery().contains("state=state-value"));
    assertTrue(result.getRawQuery().contains("code_challenge=challenge-value"));
    assertTrue(result.getRawQuery().contains("code_challenge_method=S256"));
  }
}
