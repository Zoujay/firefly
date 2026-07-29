package firefly.dao.stagebuild;


import firefly.constant.BuildStatus;
import firefly.model.stage.StageBuild;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IStageBuildDao extends JpaRepository<StageBuild, Long> {

    @Query("""
            select s from StageBuild as s, StageModel as c
            where s.pipelineBuildID = ?1 and s.stageID = c.id
            order by c.stageOrder asc
            """)
    List<StageBuild> getStageBuildByPipelineBuildID(Long pipelineBuildID);

    @Modifying
    @Query("update StageBuild s set s.stageStatus = ?1 where s.id = ?2")
    Integer updateStageBuildStatusByID(BuildStatus status, Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update StageBuild s
            set s.stageStatus = :targetStatus
            where s.id = :stageBuildID
              and s.stageStatus = :expectedStatus
            """)
    Integer transitionStageBuildStatus(
            @Param("stageBuildID") Long stageBuildID,
            @Param("expectedStatus") BuildStatus expectedStatus,
            @Param("targetStatus") BuildStatus targetStatus);

    @Query("""
            select s from StageBuild as s
            where s.stageID = ?1 and s.pipelineBuildID = ?2
            """)
    Optional<StageBuild> getStageBuildByStageConfigIDAndPipelineBuildID(
            Long stageConfigID, Long pipelineBuildID);


}
