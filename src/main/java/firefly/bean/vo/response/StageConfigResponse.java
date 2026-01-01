package firefly.bean.vo.response;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class StageConfigResponse {
    private Long id;
    private Long pipelineID;
    private String uuid;
    private String name;
    private List<JobConfigResponse> jobs;

}
