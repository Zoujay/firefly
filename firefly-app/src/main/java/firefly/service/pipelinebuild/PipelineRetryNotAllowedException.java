package firefly.service.pipelinebuild;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class PipelineRetryNotAllowedException extends RuntimeException {

    public PipelineRetryNotAllowedException(String message) {
        super(message);
    }
}
