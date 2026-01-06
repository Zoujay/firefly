package firefly.dao.stagebuild;


import firefly.constant.BuildStatus;
import firefly.model.stage.StageBuild;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IStageBuildDao extends JpaRepository<StageBuild, Long> {

    @Query("select s from StageBuild as s where s.pipelineBuildID = ?1")
    List<StageBuild> getStageBuildByPipelineBuildID(Long pipelineBuildID);

    @Modifying
    @Query("update StageBuild s set s.stageStatus = ?1 where s.id = ?2")
    Integer updateStageBuildStatusByID(BuildStatus status, Long id);


    @Query("select s from StageBuild as s where s.stageID = ?1")
    Optional<StageBuild> getStageBuildByStageConfigID(Long stageConfigID);


}
