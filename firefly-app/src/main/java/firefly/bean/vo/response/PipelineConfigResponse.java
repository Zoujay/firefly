package firefly.bean.vo.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PipelineConfigResponse {
  private Long id;
  private String uuid;
  private String name;
  private String TriggerMode;
  private String TriggerMatch;
  private String TriggerOrigin;
  private String branchPattern;
  private List<StageConfigResponse> stageConfigs;
}
