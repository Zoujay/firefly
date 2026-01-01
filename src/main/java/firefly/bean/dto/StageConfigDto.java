package firefly.bean.dto;


import lombok.*;
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

}
