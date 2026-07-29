package firefly.dao.message;

import firefly.model.message.PluginMessage;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface IPluginMessageDao extends IKafkaMessageDao<PluginMessage> {

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO plugin_message
                (message_uuid, topic, kafka_partition, kafka_offset, message_key, payload)
            VALUES
                (:messageUUID, :topic, :kafkaPartition, :kafkaOffset, :messageKey, :payload)
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("messageUUID") String messageUUID,
            @Param("topic") String topic,
            @Param("kafkaPartition") Integer kafkaPartition,
            @Param("kafkaOffset") Long kafkaOffset,
            @Param("messageKey") String messageKey,
            @Param("payload") String payload
    );
}
