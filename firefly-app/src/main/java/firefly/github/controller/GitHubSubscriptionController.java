package firefly.github.controller;

import firefly.github.api.GitHubRepository;
import firefly.github.dto.GitHubSubscriptionRequest;
import firefly.github.dto.GitHubSubscriptionResponse;
import firefly.github.service.GitHubSubscriptionService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/github")
public class GitHubSubscriptionController {

    private final GitHubSubscriptionService subscriptionService;

    public GitHubSubscriptionController(GitHubSubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("/connections/{connectionId}/repositories")
    public List<GitHubRepository> repositories(@PathVariable String connectionId) {
        return subscriptionService.repositories(connectionId);
    }

    @PutMapping("/connections/{connectionId}/repositories/{owner}/{repository}/subscription")
    public GitHubSubscriptionResponse upsert(
            @PathVariable String connectionId,
            @PathVariable String owner,
            @PathVariable String repository,
            @Valid @RequestBody GitHubSubscriptionRequest request) {
        return subscriptionService.upsert(connectionId, owner, repository, request);
    }

    @DeleteMapping("/subscriptions/{subscriptionId}")
    public ResponseEntity<Void> delete(@PathVariable String subscriptionId) {
        subscriptionService.delete(subscriptionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/subscriptions/{subscriptionId}/ping")
    public ResponseEntity<Void> ping(@PathVariable String subscriptionId) {
        subscriptionService.ping(subscriptionId);
        return ResponseEntity.accepted().build();
    }
}
