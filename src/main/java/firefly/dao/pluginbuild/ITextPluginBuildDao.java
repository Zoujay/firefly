package firefly.dao.pluginbuild;

import firefly.constant.BuildStatus;
import firefly.model.plugin.TextPluginBuild;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ITextPluginBuildDao extends JpaRepository<TextPluginBuild, Long> {

    @Modifying
    @Query("update TextPluginBuild t set t.textPluginStatus = ?2 where t.id = ?1")
    Integer updatePluginBuildStatus(Long id, BuildStatus status);

    @Query("select t.jobBuildID from TextPluginBuild t where t.id = ?1")
    Long getJobBuildIDByPluginBuildID(Long id);

}
