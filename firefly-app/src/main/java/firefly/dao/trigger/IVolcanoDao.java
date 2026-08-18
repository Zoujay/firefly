package firefly.dao.trigger;

import firefly.model.origin.VolcanoEngine;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface IVolcanoDao extends JpaRepository<VolcanoEngine, Long> {

  @Query("select v from VolcanoEngine v where v.pipelineID = ?1")
  Optional<VolcanoEngine> findByPipelineID(Long pipelineID);
}
