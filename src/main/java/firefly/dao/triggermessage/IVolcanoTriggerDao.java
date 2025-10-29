package firefly.dao.triggermessage;

import firefly.model.trigger.VolcanoTriggerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IVolcanoTriggerDao extends JpaRepository<VolcanoTriggerEntity, Long> {
}
