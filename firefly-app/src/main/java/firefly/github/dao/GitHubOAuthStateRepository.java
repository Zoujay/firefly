package firefly.github.dao;

import firefly.github.model.GitHubOAuthStateEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GitHubOAuthStateRepository extends JpaRepository<GitHubOAuthStateEntity, Long> {

  Optional<GitHubOAuthStateEntity> findByState(String state);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      delete from GitHubOAuthStateEntity s
       where s.id = :id
         and s.consumedAt is null
      """)
  int consumePending(@Param("id") Long id);
}
