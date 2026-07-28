package firefly.dao.message;

import firefly.model.message.PipelineMessage;
import org.springframework.stereotype.Repository;

@Repository
public interface IPipelineMessageDao extends IKafkaMessageDao<PipelineMessage> {
}
