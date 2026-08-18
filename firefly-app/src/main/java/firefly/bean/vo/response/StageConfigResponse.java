package firefly.bean.vo.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class StageConfigResponse {
  private Long id;
  private Long pipelineID;
  private String uuid;
  private String name;
  private Integer stageOrder;
  private List<List<JobConfigResponse>> jobs;
}
