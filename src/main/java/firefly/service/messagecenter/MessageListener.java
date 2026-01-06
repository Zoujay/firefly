package firefly.service.messagecenter;

import com.google.gson.Gson;
import firefly.bean.dto.message.TriggerJobMessage;
import firefly.bean.dto.message.TriggerPipelineMessage;
import firefly.bean.dto.message.TriggerPluginMessage;
import firefly.bean.dto.message.TriggerStageMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Semaphore;

import static firefly.constant.KafkaConfiguration.*;

@Slf4j
@Component
public class MessageListener {

    private static final int MAX_VIRTUAL_THREAD_NUMBER = 1000;

    @Autowired
    private MessageCenter messageCenter;

    private Gson gson = new Gson();

    private static Semaphore SEMAPHORE = new Semaphore(MAX_VIRTUAL_THREAD_NUMBER);

    @KafkaListener(topics = PIPELINE_TOPIC)
    public void onPipelineMessage(List<String> messages, Acknowledgment ack) {
        System.out.println("message is " + messages);
        for (String message : messages) {
            TriggerPipelineMessage triggerPipelineMessage = gson.fromJson(message, TriggerPipelineMessage.class);
            // modify pipeline status
            Thread.startVirtualThread(() -> {
                try {
                    SEMAPHORE.acquire();
                    log.info("{} acquire semaphore, message UUID is {}", Thread.currentThread().getName(), triggerPipelineMessage.toString());
                    Boolean result = messageCenter.onPipelineMessage(triggerPipelineMessage);
                    // send stage message
                    // modify stage status
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } finally {
                    ack.acknowledge();
                    SEMAPHORE.release();
                }
            });
        }
    }


    @KafkaListener(topics = STAGE_TOPIC)
    public void onStageMessage(List<String> messages, Acknowledgment ack) {
        System.out.println(messages);
        for (String message : messages) {
            TriggerStageMessage triggerStageMessage = gson.fromJson(message, TriggerStageMessage.class);
            // modify pipeline status
            Thread.startVirtualThread(() -> {

                try {
                    SEMAPHORE.acquire();
                    Boolean result = messageCenter.onStageMessage(triggerStageMessage);
                    // send stage message
                    // modify stage status
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } finally {
                    ack.acknowledge();
                    SEMAPHORE.release();
                }

            });
        }
    }


    @KafkaListener(topics = JOB_TOPIC)
    public void onJobMessage(List<String> messages, Acknowledgment ack) {
        System.out.println(messages);
        for (String message : messages) {
            Thread.startVirtualThread(() -> {
                // modify pipeline status
                try {
                    SEMAPHORE.acquire();
                    TriggerJobMessage triggerJobMessage = gson.fromJson(message, TriggerJobMessage.class);
                    Boolean result = messageCenter.onJobMessage(triggerJobMessage);
                    // send stage message
                    // modify stage status
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } finally {
                    ack.acknowledge();
                    SEMAPHORE.release();
                }

            });
        }
    }


    @KafkaListener(topics = PLUGIN_TOPIC)
    public void onPluginMessage(List<String> messages, Acknowledgment ack) {
        System.out.println(messages);
        for (String message : messages) {
            Thread.startVirtualThread(() -> {
                try {
                    SEMAPHORE.acquire();
                    TriggerPluginMessage triggerPluginMessage = gson.fromJson(message, TriggerPluginMessage.class);
                    // modify pipeline status
                    Boolean result = messageCenter.onPluginMessage(triggerPluginMessage);
                    // send stage message
                    // modify stage status
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } finally {
                    ack.acknowledge();
                    SEMAPHORE.release();
                }
            });
        }

    }


}
