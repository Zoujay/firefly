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
public class JobBuildDto {
    private Long stageBuildID;
    private Long jobConfigID;
    private Long jobBuildID;
    private BuildStatus status;
}
