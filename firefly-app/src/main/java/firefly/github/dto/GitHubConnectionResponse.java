package firefly.github.dto;

import firefly.github.model.GitHubConnectionStatus;

import java.util.List;

public record GitHubConnectionResponse(
    String connectionId,
    Long githubUserId,
    String login,
    GitHubConnectionStatus status,
    List<String> scopes) {

}
