package firefly.github.service;

import firefly.github.config.GitHubProperties;
import firefly.github.dao.GitHubConnectionRepository;
import firefly.github.dto.GitHubConnectionResponse;
import firefly.github.oauth.GitHubOAuthClient;
import firefly.github.oauth.GitHubOAuthResult;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class GitHubConnectionService {

  private final GitHubOAuthStateService stateService;
  private final GitHubOAuthClient oauthClient;
  private final GitHubConnectionWriter connectionWriter;
  private final GitHubConnectionRepository connectionRepository;
  private final GitHubProperties properties;

  public GitHubConnectionService(
      GitHubOAuthStateService stateService,
      GitHubOAuthClient oauthClient,
      GitHubConnectionWriter connectionWriter,
      GitHubConnectionRepository connectionRepository,
      GitHubProperties properties) {
    this.stateService = stateService;
    this.oauthClient = oauthClient;
    this.connectionWriter = connectionWriter;
    this.connectionRepository = connectionRepository;
    this.properties = properties;
  }

  public GitHubConnectionResponse complete(String code, String state, String browserSession) {
    String verifier = stateService.consume(state, browserSession);
    GitHubOAuthResult result = oauthClient.exchange(code, verifier);
    validateScopes(result);
    try {
      return connectionWriter.save(result);
    } catch (DataIntegrityViolationException exception) {
      return connectionWriter.save(result);
    }
  }

  public List<GitHubConnectionResponse> list() {
    return connectionRepository.findAll().stream().map(connectionWriter::response).toList();
  }

  private void validateScopes(GitHubOAuthResult result) {
    Set<String> actual =
        result.token().scope() == null
            ? Set.of()
            : List.of(result.token().scope().split(",")).stream()
                .map(String::trim)
                .filter(scope -> !scope.isBlank())
                .collect(Collectors.toSet());
    List<String> missing =
        properties.getScopes().stream().filter(scope -> !actual.contains(scope)).toList();
    if (!missing.isEmpty()) {
      throw new firefly.github.http.GitHubIntegrationException(
          org.springframework.http.HttpStatus.FORBIDDEN,
          "GITHUB_OAUTH_SCOPE_INSUFFICIENT",
          "GitHub authorization did not grant all required scopes");
    }
  }
}
