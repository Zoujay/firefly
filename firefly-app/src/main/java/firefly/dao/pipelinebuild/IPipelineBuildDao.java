package firefly.dao.pipelinebuild;

import firefly.constant.BuildStatus;
import firefly.model.pipeline.PipelineBuild;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface IPipelineBuildDao extends JpaRepository<PipelineBuild, Long> {
  @Modifying
  @Query(
      """
      update PipelineBuild p
      set p.pipelineStatus = :status
      where p.id = :pipelineBuildID
        and p.executionAttempt = :executionAttempt
      """)
  int updatePipelineBuildStatus(
      @Param("pipelineBuildID") Long pipelineBuildID,
      @Param("status") BuildStatus status,
      @Param("executionAttempt") Integer executionAttempt);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update PipelineBuild p
      set p.pipelineStatus = :targetStatus,
          p.executionAttempt = p.executionAttempt + 1
      where p.id = :pipelineBuildID
        and p.pipelineStatus = :expectedStatus
      """)
  int claimRetry(
      @Param("pipelineBuildID") Long pipelineBuildID,
      @Param("expectedStatus") BuildStatus expectedStatus,
      @Param("targetStatus") BuildStatus targetStatus);
}
