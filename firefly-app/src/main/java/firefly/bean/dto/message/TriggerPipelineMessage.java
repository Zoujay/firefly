package firefly.bean.dto.message;

import firefly.constant.BuildStatus;
import java.io.Serial;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class TriggerPipelineMessage implements Serializable, KafkaBusinessMessage {

  @Serial private static final long serialVersionUID = -823258082885393580L;
  private String messageUUID;

  private Long pipelineID;

  private Long pipelineBuildID;

  private BuildStatus buildStatus;

  private Integer executionAttempt = 0;
}
