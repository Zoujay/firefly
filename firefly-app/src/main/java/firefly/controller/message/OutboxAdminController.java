package firefly.controller.message;

import firefly.bean.vo.response.OutboxEventResponse;
import firefly.constant.OutboxStatus;
import firefly.service.outbox.OutboxEventNotFoundException;
import firefly.service.outbox.OutboxPublisher;
import firefly.service.outbox.OutboxStateService;

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
@RequestMapping("/admin/outbox-events")
public class OutboxAdminController {

    @Autowired private OutboxStateService stateService;

    @Autowired private OutboxPublisher outboxPublisher;

    @GetMapping("/{outboxID}")
    public OutboxEventResponse getEvent(@PathVariable Long outboxID) {
        return stateService.getResponse(outboxID);
    }

    @GetMapping
    public Page<OutboxEventResponse> getEvents(
            @RequestParam OutboxStatus status, Pageable pageable) {
        return stateService.getResponses(status, pageable);
    }

    @PostMapping("/{outboxID}/publish")
    public OutboxEventResponse publish(@PathVariable Long outboxID) {
        OutboxEventResponse current = stateService.getResponse(outboxID);
        if (current.getPublishStatus() == OutboxStatus.SENT) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A SENT Outbox event cannot be published again");
        }
        if (current.getPublishStatus() == OutboxStatus.PUBLISHING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Reset the PUBLISHING event before manual retry");
        }
        outboxPublisher.publishOnce(outboxID);
        return stateService.getResponse(outboxID);
    }

    @PostMapping("/{outboxID}/reset-publishing")
    public OutboxEventResponse resetPublishing(
            @PathVariable Long outboxID,
            @RequestParam @NotBlank String publisherID,
            @RequestParam(defaultValue = "MANUAL_RESET") String reason) {
        if (!stateService.resetPublishing(outboxID, publisherID, reason)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Outbox event is not PUBLISHING or publisherID does not match");
        }
        return stateService.getResponse(outboxID);
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(OutboxEventNotFoundException.class)
    public String notFound(OutboxEventNotFoundException exception) {
        return exception.getMessage();
    }
}
