package firefly.dao.stageconfig;

import firefly.model.stage.StageModel;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IStageConfigDao extends JpaRepository<StageModel, Long> {
    @Query("select s from StageModel s where s.pipeline_id = ?1 order by s.stageOrder asc")
    List<StageModel> getStageConfigByPipelineID(Long pipelineID);
}
