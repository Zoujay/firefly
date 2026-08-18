package firefly.github.dao;

import firefly.github.model.GitHubConnectionEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GitHubConnectionRepository extends JpaRepository<GitHubConnectionEntity, Long> {

  Optional<GitHubConnectionEntity> findBySingletonKey(String singletonKey);

  Optional<GitHubConnectionEntity> findByPublicId(String publicId);
}
