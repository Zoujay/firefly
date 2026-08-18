package firefly.bean.vo.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PipelineRetryResponse {

    private Long pipelineBuildID;
    private Integer executionAttempt;
}
