package firefly.bean.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class PipelineConfigDto {
    private Long id;
    private String uuid;
    private String name;
    private String triggerMode;
    private String triggerMatch;
    private String triggerOrigin;
}
