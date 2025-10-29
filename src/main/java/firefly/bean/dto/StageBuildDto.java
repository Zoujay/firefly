package firefly.bean.dto;

import firefly.constant.BuildStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class StageBuildDto {
    private Long stageBuildID;
    private Long stageConfigID;
    private Long pipelineBuildID;
    private BuildStatus status;

}
