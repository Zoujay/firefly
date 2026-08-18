package firefly.dao.stagebuild;

import firefly.constant.BuildStatus;
import firefly.model.stage.StageBuild;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IStageBuildDao extends JpaRepository<StageBuild, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            select s from StageBuild s
            where s.id = :stageBuildID
              and s.executionAttempt = :executionAttempt
            """)
    Optional<StageBuild> findForUpdate(
            @Param("stageBuildID") Long stageBuildID,
            @Param("executionAttempt") Integer executionAttempt);

    @Query(
            """
            select s from StageBuild as s, StageModel as c
            where s.pipelineBuildID = ?1 and s.stageID = c.id
            order by c.stageOrder asc
            """)
    List<StageBuild> getStageBuildByPipelineBuildID(Long pipelineBuildID);

    @Modifying
    @Query(
            """
            update StageBuild s
            set s.stageStatus = :status
            where s.id = :id
              and s.executionAttempt = :executionAttempt
            """)
    Integer updateStageBuildStatusByID(
            @Param("status") BuildStatus status,
            @Param("id") Long id,
            @Param("executionAttempt") Integer executionAttempt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update StageBuild s
            set s.stageStatus = :targetStatus
            where s.id = :stageBuildID
              and s.stageStatus = :expectedStatus
              and s.executionAttempt = :executionAttempt
            """)
    Integer transitionStageBuildStatus(
            @Param("stageBuildID") Long stageBuildID,
            @Param("expectedStatus") BuildStatus expectedStatus,
            @Param("targetStatus") BuildStatus targetStatus,
            @Param("executionAttempt") Integer executionAttempt);

    @Query(
            """
            select s from StageBuild as s
            where s.stageID = ?1 and s.pipelineBuildID = ?2
            """)
    Optional<StageBuild> getStageBuildByStageConfigIDAndPipelineBuildID(
            Long stageConfigID, Long pipelineBuildID);
}
