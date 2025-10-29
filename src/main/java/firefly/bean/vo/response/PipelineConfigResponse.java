package firefly.bean.vo.response;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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
    private List<StageConfigResponse> stageConfigs;

}
