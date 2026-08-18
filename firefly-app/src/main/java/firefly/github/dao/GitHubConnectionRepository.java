package firefly.github.dao;

import firefly.github.model.GitHubConnectionEntity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GitHubConnectionRepository extends JpaRepository<GitHubConnectionEntity, Long> {

    Optional<GitHubConnectionEntity> findBySingletonKey(String singletonKey);

    Optional<GitHubConnectionEntity> findByPublicId(String publicId);
}
