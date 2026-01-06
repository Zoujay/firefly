package firefly.dao.jobbuild;

import firefly.constant.BuildStatus;
import firefly.model.job.JobBuild;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IJobBuildDao extends JpaRepository<JobBuild, Long> {
    @Query("select j from JobBuild as j where j.stageBuildID = ?1")
    List<JobBuild> getJobBuildsByStageBuildID(Long stageBuildID);

    @Modifying
    @Query("update JobBuild j set j.jobStatus = ?2 where j.id = ?1")
    Integer updateJobBuildStatusByID(Long jobBuildID, BuildStatus status);

    @Query("select j from JobBuild as j where j.jobID = ?1 and j.stageBuildID = ?2 order by j.id desc limit 1")
    Optional<JobBuild> getJobBuildByJobConfigIDAndStageBuildID(Long jobConfigID, Long stageBuildID);

}
