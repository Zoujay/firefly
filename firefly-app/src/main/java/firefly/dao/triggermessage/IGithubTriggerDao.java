package firefly.dao.triggermessage;

import firefly.model.trigger.GithubTriggerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IGithubTriggerDao extends JpaRepository<GithubTriggerEntity, Long> {
}
