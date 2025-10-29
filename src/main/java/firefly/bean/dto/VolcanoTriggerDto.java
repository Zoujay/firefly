package firefly.bean.dto;

import lombok.*;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

@EqualsAndHashCode(callSuper = true)
@ToString
@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
public class VolcanoTriggerDto extends BaseTriggerOriginDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 6321474757460847351L;

    private Long volcanoID;


    private Long pipelineID;

    private String ak;

    private String sk;

}
