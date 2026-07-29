package firefly.dao.message;

import firefly.model.message.KafkaMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

@NoRepositoryBean
public interface IKafkaMessageDao<T extends KafkaMessage> extends JpaRepository<T, Long> {

    long countByMessageUUID(String messageUUID);
}
