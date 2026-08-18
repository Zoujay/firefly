package firefly.github.api;

import firefly.github.config.GitHubProperties;
import firefly.github.http.GitHubIntegrationException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class GitHubApiClient {

  private static final Pattern REPOSITORY_PART = Pattern.compile("[A-Za-z0-9_.-]+");
  private final GitHubProperties properties;
  private final RestClient restClient;

  public GitHubApiClient(GitHubProperties properties, RestClient.Builder restClientBuilder) {
    this.properties = properties;
    this.restClient = restClientBuilder.clone().build();
  }

  public List<GitHubRepository> listRepositories(String token) {
    requireToken(token);
    try {
      List<GitHubRepository> repositories =
          authenticate(
                  restClient
                      .get()
                      .uri(
                          properties
                              .getApiBaseUrl()
                              .resolve(
                                  "/user/repos?per_page=100&sort=updated&affiliation=owner,collaborator,organization_member")),
                  token)
              .retrieve()
              .body(new ParameterizedTypeReference<>() {});
      return repositories == null ? List.of() : List.copyOf(repositories);
    } catch (RestClientException exception) {
      throw upstream("Failed to list GitHub repositories", exception);
    }
  }

  public GitHubRepository getRepository(String token, String owner, String repository) {
    validateRepository(owner, repository);
    try {
      GitHubRepository result =
          authenticate(restClient.get().uri(repositoryUri(owner, repository, "")), token)
              .retrieve()
              .body(GitHubRepository.class);
      if (result == null || result.id() == null) {
        throw upstream("GitHub returned an invalid repository", null);
      }
      return result;
    } catch (GitHubIntegrationException exception) {
      throw exception;
    } catch (RestClientException exception) {
      throw upstream("Failed to read GitHub repository", exception);
    }
  }

  public List<GitHubWebhook> listWebhooks(String token, String owner, String repository) {
    validateRepository(owner, repository);
    try {
      List<GitHubWebhook> hooks =
          authenticate(
                  restClient.get().uri(repositoryUri(owner, repository, "/hooks?per_page=100")),
                  token)
              .retrieve()
              .body(new ParameterizedTypeReference<>() {});
      return hooks == null ? List.of() : List.copyOf(hooks);
    } catch (RestClientException exception) {
      throw upstream("Failed to list GitHub webhooks", exception);
    }
  }

  public GitHubWebhook createWebhook(
      String token,
      String owner,
      String repository,
      URI callbackUrl,
      String secret,
      List<String> events) {
    validateRepository(owner, repository);
    validateWebhook(callbackUrl, secret, events);
    Map<String, Object> body =
        Map.of(
            "name",
            "web",
            "active",
            true,
            "events",
            List.copyOf(events),
            "config",
            Map.of(
                "url",
                callbackUrl.toString(),
                "content_type",
                "json",
                "secret",
                secret,
                "insecure_ssl",
                "0"));
    try {
      GitHubWebhook hook =
          authenticate(
                  restClient.post().uri(repositoryUri(owner, repository, "/hooks")).body(body),
                  token)
              .retrieve()
              .body(GitHubWebhook.class);
      if (hook == null || hook.id() == null) {
        throw upstream("GitHub returned an invalid webhook", null);
      }
      return hook;
    } catch (GitHubIntegrationException exception) {
      throw exception;
    } catch (RestClientException exception) {
      throw upstream("Failed to create GitHub webhook", exception);
    }
  }

  public GitHubWebhook updateWebhook(
      String token,
      String owner,
      String repository,
      Long webhookId,
      URI callbackUrl,
      String secret,
      List<String> events) {
    validateRepository(owner, repository);
    requireWebhookId(webhookId);
    validateWebhook(callbackUrl, secret, events);
    Map<String, Object> body =
        Map.of(
            "active", true,
            "events", List.copyOf(events),
            "config",
                Map.of(
                    "url",
                    callbackUrl.toString(),
                    "content_type",
                    "json",
                    "secret",
                    secret,
                    "insecure_ssl",
                    "0"));
    try {
      GitHubWebhook hook =
          authenticate(
                  restClient
                      .patch()
                      .uri(repositoryUri(owner, repository, "/hooks/" + webhookId))
                      .body(body),
                  token)
              .retrieve()
              .body(GitHubWebhook.class);
      if (hook == null || hook.id() == null) {
        throw upstream("GitHub returned an invalid webhook", null);
      }
      return hook;
    } catch (GitHubIntegrationException exception) {
      throw exception;
    } catch (RestClientException exception) {
      throw upstream("Failed to update GitHub webhook", exception);
    }
  }

  public void pingWebhook(String token, String owner, String repository, Long webhookId) {
    validateRepository(owner, repository);
    requireWebhookId(webhookId);
    try {
      authenticate(
              restClient
                  .post()
                  .uri(repositoryUri(owner, repository, "/hooks/" + webhookId + "/pings")),
              token)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException exception) {
      throw upstream("Failed to ping GitHub webhook", exception);
    }
  }

  public void deleteWebhook(String token, String owner, String repository, Long webhookId) {
    validateRepository(owner, repository);
    requireWebhookId(webhookId);
    try {
      authenticate(
              restClient.delete().uri(repositoryUri(owner, repository, "/hooks/" + webhookId)),
              token)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException exception) {
      throw upstream("Failed to delete GitHub webhook", exception);
    }
  }

  private RestClient.RequestHeadersSpec<?> authenticate(
      RestClient.RequestHeadersSpec<?> request, String token) {
    requireToken(token);
    return request
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
        .header("X-GitHub-Api-Version", properties.getApiVersion())
        .header(HttpHeaders.USER_AGENT, properties.getUserAgent());
  }

  private URI repositoryUri(String owner, String repository, String suffix) {
    return properties.getApiBaseUrl().resolve("/repos/" + owner + "/" + repository + suffix);
  }

  private void validateRepository(String owner, String repository) {
    if (!StringUtils.hasText(owner)
        || !StringUtils.hasText(repository)
        || !REPOSITORY_PART.matcher(owner).matches()
        || !REPOSITORY_PART.matcher(repository).matches()) {
      throw new GitHubIntegrationException(
          HttpStatus.BAD_REQUEST,
          "GITHUB_REPOSITORY_INVALID",
          "GitHub owner or repository name is invalid");
    }
  }

  private void validateWebhook(URI callbackUrl, String secret, List<String> events) {
    if (callbackUrl == null || !"https".equalsIgnoreCase(callbackUrl.getScheme())) {
      throw new GitHubIntegrationException(
          HttpStatus.BAD_REQUEST,
          "GITHUB_WEBHOOK_URL_INVALID",
          "GitHub webhook callback URL must use HTTPS");
    }
    if (!StringUtils.hasText(secret) || events == null || events.isEmpty()) {
      throw new GitHubIntegrationException(
          HttpStatus.BAD_REQUEST,
          "GITHUB_WEBHOOK_INVALID",
          "GitHub webhook secret and events are required");
    }
  }

  private void requireToken(String token) {
    if (!StringUtils.hasText(token)) {
      throw new GitHubIntegrationException(
          HttpStatus.UNAUTHORIZED,
          "GITHUB_TOKEN_REQUIRED",
          "GitHub OAuth access token is required");
    }
  }

  private void requireWebhookId(Long webhookId) {
    if (webhookId == null || webhookId <= 0) {
      throw new IllegalArgumentException("GitHub webhook ID must be positive");
    }
  }

  private GitHubIntegrationException upstream(String message, Throwable cause) {
    return new GitHubIntegrationException(
        HttpStatus.BAD_GATEWAY, "GITHUB_UPSTREAM_ERROR", message, cause);
  }
}
