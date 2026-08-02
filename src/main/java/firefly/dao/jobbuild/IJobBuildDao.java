package firefly.dao.jobbuild;

import firefly.constant.BuildStatus;
import firefly.model.job.JobBuild;
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
public interface IJobBuildDao extends JpaRepository<JobBuild, Long> {
    @Query("select j from JobBuild as j where j.stageBuildID = ?1")
    List<JobBuild> getJobBuildsByStageBuildID(Long stageBuildID);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from JobBuild as j where j.stageBuildID = :stageBuildID")
    List<JobBuild> getJobBuildsByStageBuildIDForUpdate(
            @Param("stageBuildID") Long stageBuildID
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update JobBuild j
            set j.jobStatus = :targetStatus
            where j.id = :jobBuildID
              and j.executionAttempt = :executionAttempt
              and j.jobStatus = :expectedStatus
            """)
    Integer transitionJobBuildStatus(
            @Param("jobBuildID") Long jobBuildID,
            @Param("expectedStatus") BuildStatus expectedStatus,
            @Param("targetStatus") BuildStatus targetStatus,
            @Param("executionAttempt") Integer executionAttempt
    );

    @Query("select j from JobBuild as j where j.jobID = ?1 and j.stageBuildID = ?2 order by j.id desc limit 1")
    Optional<JobBuild> getJobBuildByJobConfigIDAndStageBuildID(Long jobConfigID, Long stageBuildID);

}
