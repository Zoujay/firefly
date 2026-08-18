package firefly.github.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import firefly.github.api.GitHubApiClient;
import firefly.github.oauth.GitHubOAuthClient;
import firefly.github.oauth.PkceGenerator;
import firefly.github.webhook.GitHubWebhookEventParser;
import firefly.github.webhook.GitHubWebhookSignatureVerifier;
import java.security.SecureRandom;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@AutoConfiguration
@EnableConfigurationProperties(GitHubProperties.class)
public class FireflyGitHubAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public PkceGenerator pkceGenerator() {
    return new PkceGenerator(new SecureRandom());
  }

  @Bean
  @ConditionalOnMissingBean
  public GitHubOAuthClient gitHubOAuthClient(
      GitHubProperties properties, RestClient.Builder restClientBuilder) {
    return new GitHubOAuthClient(properties, restClientBuilder);
  }

  @Bean
  @ConditionalOnMissingBean
  public GitHubApiClient gitHubApiClient(
      GitHubProperties properties, RestClient.Builder restClientBuilder) {
    return new GitHubApiClient(properties, restClientBuilder);
  }

  @Bean
  @ConditionalOnMissingBean
  public GitHubWebhookSignatureVerifier gitHubWebhookSignatureVerifier() {
    return new GitHubWebhookSignatureVerifier();
  }

  @Bean
  @ConditionalOnMissingBean
  public GitHubWebhookEventParser gitHubWebhookEventParser(ObjectMapper objectMapper) {
    return new GitHubWebhookEventParser(objectMapper);
  }
}
