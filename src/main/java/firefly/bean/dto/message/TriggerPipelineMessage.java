package firefly.bean.dto.message;


import firefly.constant.BuildStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class TriggerPipelineMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = -823258082885393580L;
    private String messageUUID;

    private Long pipelineID;

    private Long pipelineBuildID;

    private BuildStatus buildStatus;
}
