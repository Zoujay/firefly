package firefly.dao.jobconfig;

import firefly.model.job.JobRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IJobRelationDao extends JpaRepository<JobRelation, Long> {

    @Query("select j from JobRelation j where j.stageID = ?1")
    List<JobRelation> getJobRelationsByStageID(Long stageID);

    @Query("select j from JobRelation j where j.stageID = ?1 and j.isHeadJob = ?2")
    List<JobRelation> getAllHeadJobRelationsByStageID(Long stageID, Boolean isHeadJob);

    @Query("select j from JobRelation j where j.stageID = ?1 and j.nextJobID = 0")
    List<JobRelation> getAllTailJobRelationsByStageID(Long stageID);

}
