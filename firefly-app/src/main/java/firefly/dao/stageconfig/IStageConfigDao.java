package firefly.dao.stageconfig;

import firefly.model.stage.StageModel;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface IStageConfigDao extends JpaRepository<StageModel, Long> {
  @Query("select s from StageModel s where s.pipeline_id = ?1 order by s.stageOrder asc")
  List<StageModel> getStageConfigByPipelineID(Long pipelineID);
}
