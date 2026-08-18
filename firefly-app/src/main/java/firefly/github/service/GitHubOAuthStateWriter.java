package firefly.github.service;

import firefly.github.dao.GitHubOAuthStateRepository;
import firefly.github.model.GitHubOAuthStateEntity;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class GitHubOAuthStateWriter {

    private final GitHubOAuthStateRepository stateRepository;

    public GitHubOAuthStateWriter(GitHubOAuthStateRepository stateRepository) {
        this.stateRepository = stateRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<GitHubOAuthStateEntity> take(String state) {
        Optional<GitHubOAuthStateEntity> pending = stateRepository.findByState(state);
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        return stateRepository.consumePending(pending.get().getId()) == 1
            ? pending
            : Optional.empty();
    }
}
