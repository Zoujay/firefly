package firefly.bean.dto.message;

import firefly.constant.TriggerOrigin;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
public class BaseMessage {
    private Long pipelineBuildID;
    private Long pipelineID;
    private TriggerOrigin triggerOrigin;
    private Long triggerID;
}
