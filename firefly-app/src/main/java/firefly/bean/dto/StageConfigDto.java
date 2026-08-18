package firefly.bean.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class StageConfigDto {

  private Long id;
  private Long pipelineID;
  private String uuid;
  private String name;
  private Integer stageOrder;
}
