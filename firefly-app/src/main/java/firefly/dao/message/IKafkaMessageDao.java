package firefly.dao.message;

import firefly.constant.MessageProcessingStatus;
import firefly.model.message.KafkaMessage;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@NoRepositoryBean
public interface IKafkaMessageDao<T extends KafkaMessage> extends JpaRepository<T, Long> {

    long countByMessageUUID(String messageUUID);

    Optional<T> findByMessageUUID(String messageUUID);

    Page<T> findByProcessingStatusOrderByReceivedAtAsc(
            MessageProcessingStatus processingStatus, Pageable pageable);

    int claimForProcessing(
            String messageUUID,
            List<MessageProcessingStatus> expectedStatuses,
            MessageProcessingStatus targetStatus,
            String processorID,
            LocalDateTime startedAt,
            LocalDateTime unfinishedAt,
            String emptyValue);

    int markProcessingSuccess(
            String messageUUID,
            MessageProcessingStatus expectedStatus,
            MessageProcessingStatus targetStatus,
            String processorID,
            LocalDateTime finishedAt,
            String emptyValue);

    int markProcessingFailure(
            String messageUUID,
            MessageProcessingStatus expectedStatus,
            MessageProcessingStatus targetStatus,
            String processorID,
            LocalDateTime finishedAt,
            String lastError);
}
