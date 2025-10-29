package firefly.dao.jobconfig;

import firefly.model.job.JobModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IJobConfigDao extends JpaRepository<JobModel, Long> {

    @Query("select j from JobModel j where j.jobUUID = ?1")
    Optional<JobModel> getJobModelByUUID(String jobUUID);

    @Query("select j from JobModel j where j.stageID = ?1")
    List<JobModel> getJobModelsByStageID(Long stageID);
}
