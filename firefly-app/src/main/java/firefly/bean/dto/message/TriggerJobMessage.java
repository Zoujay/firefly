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
public class TriggerJobMessage implements Serializable, KafkaBusinessMessage {
  @Serial private static final long serialVersionUID = 1765961541468589051L;

  private String messageUUID;

  private Long jobBuildID;

  private BuildStatus buildStatus;

  private Integer executionAttempt = 0;
}
