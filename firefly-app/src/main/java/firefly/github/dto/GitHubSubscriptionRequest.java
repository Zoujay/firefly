package firefly.github.dto;

import firefly.github.model.GitHubRegistrationMode;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record GitHubSubscriptionRequest(
        @NotNull GitHubRegistrationMode registrationMode,
        List<String> events
) {
}
