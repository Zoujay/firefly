package firefly.github.message;

import static firefly.constant.KafkaConfiguration.GITHUB_WEBHOOK_TOPIC;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import firefly.github.service.GitHubWebhookProcessingService;

import lombok.extern.slf4j.Slf4j;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class GitHubWebhookMessageListener {

    private final ObjectMapper objectMapper;
    private final GitHubWebhookProcessingService processingService;

    public GitHubWebhookMessageListener(
            ObjectMapper objectMapper, GitHubWebhookProcessingService processingService) {
        this.objectMapper = objectMapper;
        this.processingService = processingService;
    }

    @KafkaListener(topics = GITHUB_WEBHOOK_TOPIC)
    public void onWebhookMessages(
            List<ConsumerRecord<String, String>> records, Acknowledgment acknowledgment) {
        for (ConsumerRecord<String, String> record : records) {
            try {
                GitHubWebhookMessage message =
                        objectMapper.readValue(record.value(), GitHubWebhookMessage.class);
                processingService.process(message.getDeliveryId());
            } catch (JsonProcessingException exception) {
                log.error(
                        "Invalid GitHub webhook message at {}-{}@{}",
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        exception);
            } catch (RuntimeException exception) {
                log.error(
                        "GitHub delivery processing failed at {}-{}@{}; delivery is RETRYABLE",
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        exception);
            }
        }
        acknowledgment.acknowledge();
    }
}
