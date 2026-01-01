package firefly.bean.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
public class JobRelationDto {

    private Long id;
    private Long pipelineID;
    private Long stageID;
    private Long jobID;
    private Long nextJobID;
    private Long previousJobID;
    private Boolean isHeadJob;

}
