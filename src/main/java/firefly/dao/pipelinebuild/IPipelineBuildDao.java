package firefly.dao.pipelinebuild;

import firefly.constant.BuildStatus;
import firefly.model.pipeline.PipelineBuild;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface IPipelineBuildDao extends JpaRepository<PipelineBuild, Long> {
    @Modifying
    @Query("update PipelineBuild p set p.pipelineStatus = ?2 where p.id = ?1")
    int updatePipelineBuildStatus(Long pipelineBuildID, BuildStatus status);
}
