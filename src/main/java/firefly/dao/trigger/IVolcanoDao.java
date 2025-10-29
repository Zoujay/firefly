package firefly.dao.trigger;

import firefly.model.origin.VolcanoEngine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface IVolcanoDao extends JpaRepository<VolcanoEngine, Long> {

    @Query("select v from VolcanoEngine v where v.pipelineID = ?1")
    Optional<VolcanoEngine> findByPipelineID(Long pipelineID);

}
