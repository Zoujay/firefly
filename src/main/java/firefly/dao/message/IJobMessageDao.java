package firefly.dao.message;

import firefly.model.message.JobMessage;
import org.springframework.stereotype.Repository;

@Repository
public interface IJobMessageDao extends IKafkaMessageDao<JobMessage> {
}
