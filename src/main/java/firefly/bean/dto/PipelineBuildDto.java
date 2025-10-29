package firefly.bean.dto;

import firefly.constant.BuildStatus;
import firefly.constant.TriggerOrigin;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
public class PipelineBuildDto {
    private TriggerOrigin triggerOrigin;
    private Long TriggerOriginID;
    private Long pipelineID;
    private String pipelineUUID;
    private BuildStatus buildStatus;
}
