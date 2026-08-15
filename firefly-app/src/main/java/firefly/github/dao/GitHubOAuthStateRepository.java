package firefly.github.dao;

import firefly.github.model.GitHubOAuthStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GitHubOAuthStateRepository
        extends JpaRepository<GitHubOAuthStateEntity, Long> {

    Optional<GitHubOAuthStateEntity> findByState(String state);

    void deleteByState(String state);
}
