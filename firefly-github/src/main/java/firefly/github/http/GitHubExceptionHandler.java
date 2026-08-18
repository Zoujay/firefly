package firefly.github.http;

import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GitHubExceptionHandler {

  @ExceptionHandler(GitHubIntegrationException.class)
  public ResponseEntity<GitHubErrorResponse> handle(GitHubIntegrationException exception) {
    return ResponseEntity.status(exception.getStatus())
        .body(new GitHubErrorResponse(exception.getCode(), exception.getMessage(), Instant.now()));
  }
}
