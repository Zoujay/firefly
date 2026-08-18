package firefly.controller.message;

import firefly.bean.vo.response.KafkaMessageProcessingResponse;
import firefly.constant.MessageCategory;
import firefly.constant.MessageProcessingStatus;
import firefly.service.messagecenter.KafkaMessageNotFoundException;
import firefly.service.messagecenter.KafkaMessageProcessingCoordinator;
import firefly.service.messagecenter.KafkaMessageStateService;

import jakarta.validation.constraints.NotBlank;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Validated
@RestController
@RequestMapping("/admin/kafka-messages")
public class KafkaMessageAdminController {

    @Autowired
    private KafkaMessageStateService stateService;

    @Autowired
    private KafkaMessageProcessingCoordinator processingCoordinator;

    @GetMapping("/{category}/{messageUUID}")
    public KafkaMessageProcessingResponse getMessage(
        @PathVariable MessageCategory category, @PathVariable String messageUUID) {
        return stateService.getResponse(category, messageUUID);
    }

    @GetMapping("/{category}")
    public Page<KafkaMessageProcessingResponse> getMessages(
        @PathVariable MessageCategory category,
        @RequestParam MessageProcessingStatus status,
        Pageable pageable) {
        return stateService.getResponses(category, status, pageable);
    }

    @PostMapping("/{category}/{messageUUID}/retry")
    public KafkaMessageProcessingResponse retry(
        @PathVariable MessageCategory category, @PathVariable String messageUUID) {
        KafkaMessageProcessingResponse current = stateService.getResponse(category, messageUUID);
        if (current.getProcessingStatus() == MessageProcessingStatus.SUCCESS) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "A successful Inbox message cannot be processed again");
        }
        if (current.getProcessingStatus() == MessageProcessingStatus.PROCESSING) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Reset the PROCESSING message before manual retry");
        }

        if (!processingCoordinator.process(category, messageUUID)) {
            KafkaMessageProcessingResponse result = stateService.getResponse(category, messageUUID);
            if (result.getProcessingStatus() != MessageProcessingStatus.FAILURE) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Inbox message was claimed by another processor");
            }
            return result;
        }
        return stateService.getResponse(category, messageUUID);
    }

    @PostMapping("/{category}/{messageUUID}/reset-processing")
    public KafkaMessageProcessingResponse resetProcessing(
        @PathVariable MessageCategory category,
        @PathVariable String messageUUID,
        @RequestParam @NotBlank String processorID,
        @RequestParam(defaultValue = "MANUAL_RESET") String reason) {
        if (!stateService.resetProcessing(category, messageUUID, processorID, reason)) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Inbox message is not PROCESSING or processorID does not match");
        }
        return stateService.getResponse(category, messageUUID);
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(KafkaMessageNotFoundException.class)
    public String notFound(KafkaMessageNotFoundException exception) {
        return exception.getMessage();
    }
}
