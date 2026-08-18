package firefly.dao.pluginbuild;

import firefly.constant.BuildStatus;
import firefly.model.plugin.TextPluginBuild;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ITextPluginBuildDao extends JpaRepository<TextPluginBuild, Long> {

    @Modifying
    @Query(
            """
            update TextPluginBuild t
            set t.textPluginStatus = :status
            where t.id = :id
              and t.executionAttempt = :executionAttempt
            """)
    Integer updatePluginBuildStatus(
            @Param("id") Long id,
            @Param("status") BuildStatus status,
            @Param("executionAttempt") Integer executionAttempt);

    @Query("select t.jobBuildID from TextPluginBuild t where t.id = ?1")
    Long getJobBuildIDByPluginBuildID(Long id);

    @Query("select t.id from TextPluginBuild t where t.jobBuildID = ?1")
    Long getPluginBuildIDByJobBuildID(Long jobBuildID);

    Optional<TextPluginBuild> findByJobBuildID(Long jobBuildID);
}
