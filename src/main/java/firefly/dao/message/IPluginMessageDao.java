package firefly.dao.message;

import firefly.model.message.PluginMessage;
import org.springframework.stereotype.Repository;

@Repository
public interface IPluginMessageDao extends IKafkaMessageDao<PluginMessage> {
}
