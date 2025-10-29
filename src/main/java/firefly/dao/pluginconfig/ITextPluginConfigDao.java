package firefly.dao.pluginconfig;

import firefly.model.plugin.TextPluginModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ITextPluginConfigDao extends JpaRepository<TextPluginModel, Long>{
}
