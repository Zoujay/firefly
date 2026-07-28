package firefly.dao.message;

import firefly.model.message.KafkaMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

@NoRepositoryBean
public interface IKafkaMessageDao<T extends KafkaMessage> extends JpaRepository<T, Long> {

    @Query("""
            select message.messageUUID
            from #{#entityName} message
            where message.messageUUID in :messageUUIDs
            """)
    Set<String> findExistingMessageUUIDs(@Param("messageUUIDs") List<String> messageUUIDs);

    long countByMessageUUID(String messageUUID);
}
