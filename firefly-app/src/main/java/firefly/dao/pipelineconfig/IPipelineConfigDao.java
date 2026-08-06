package firefly.dao.pipelineconfig;

import firefly.model.pipeline.PipelineModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface IPipelineConfigDao extends JpaRepository<PipelineModel, Long> {
    @Query("select p from PipelineModel p where p.pipelineUUID = ?1")
    PipelineModel getPipelineConfigByPipelineUUID(String pipelineUUID);
}
