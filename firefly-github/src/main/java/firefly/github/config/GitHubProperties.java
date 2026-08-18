package firefly.github.config;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("firefly.github")
public class GitHubProperties {

  private URI oauthBaseUrl = URI.create("https://github.com");
  private URI apiBaseUrl = URI.create("https://api.github.com");
  private String apiVersion = "2026-03-10";
  private String userAgent = "firefly";
  private String clientId = "";
  private String clientSecret = "";
  private URI redirectUri;
  private URI webhookCallbackUrl;
  private List<String> scopes = new ArrayList<>(List.of("admin:repo_hook"));
  private Duration connectTimeout = Duration.ofSeconds(3);
  private Duration readTimeout = Duration.ofSeconds(10);
  private Duration stateTtl = Duration.ofMinutes(10);

  public URI getOauthBaseUrl() {
    return oauthBaseUrl;
  }

  public void setOauthBaseUrl(URI oauthBaseUrl) {
    this.oauthBaseUrl = oauthBaseUrl;
  }

  public URI getApiBaseUrl() {
    return apiBaseUrl;
  }

  public void setApiBaseUrl(URI apiBaseUrl) {
    this.apiBaseUrl = apiBaseUrl;
  }

  public String getApiVersion() {
    return apiVersion;
  }

  public void setApiVersion(String apiVersion) {
    this.apiVersion = apiVersion;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public void setUserAgent(String userAgent) {
    this.userAgent = userAgent;
  }

  public String getClientId() {
    return clientId;
  }

  public void setClientId(String clientId) {
    this.clientId = clientId;
  }

  public String getClientSecret() {
    return clientSecret;
  }

  public void setClientSecret(String clientSecret) {
    this.clientSecret = clientSecret;
  }

  public URI getRedirectUri() {
    return redirectUri;
  }

  public void setRedirectUri(URI redirectUri) {
    this.redirectUri = redirectUri;
  }

  public URI getWebhookCallbackUrl() {
    return webhookCallbackUrl;
  }

  public void setWebhookCallbackUrl(URI webhookCallbackUrl) {
    this.webhookCallbackUrl = webhookCallbackUrl;
  }

  public List<String> getScopes() {
    return scopes;
  }

  public void setScopes(List<String> scopes) {
    this.scopes = scopes == null ? new ArrayList<>() : new ArrayList<>(scopes);
  }

  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  public void setConnectTimeout(Duration connectTimeout) {
    this.connectTimeout = connectTimeout;
  }

  public Duration getReadTimeout() {
    return readTimeout;
  }

  public void setReadTimeout(Duration readTimeout) {
    this.readTimeout = readTimeout;
  }

  public Duration getStateTtl() {
    return stateTtl;
  }

  public void setStateTtl(Duration stateTtl) {
    this.stateTtl = stateTtl;
  }
}
