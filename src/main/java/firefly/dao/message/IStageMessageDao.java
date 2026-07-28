package firefly.dao.message;

import firefly.model.message.StageMessage;
import org.springframework.stereotype.Repository;

@Repository
public interface IStageMessageDao extends IKafkaMessageDao<StageMessage> {
}
