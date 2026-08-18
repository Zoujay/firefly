package firefly.bean.dto;

import java.io.Serial;
import java.io.Serializable;
import lombok.*;
import lombok.experimental.Accessors;

@EqualsAndHashCode(callSuper = true)
@ToString
@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
public class VolcanoTriggerDto extends BaseTriggerOriginDto implements Serializable {

  @Serial private static final long serialVersionUID = 6321474757460847351L;

  private Long volcanoID;

  private Long pipelineID;

  private String ak;

  private String sk;
}
