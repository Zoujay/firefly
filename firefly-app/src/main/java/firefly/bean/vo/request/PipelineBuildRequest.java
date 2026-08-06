package firefly.bean.vo.request;

import firefly.constant.TriggerMatch;
import firefly.constant.TriggerModel;
import firefly.constant.TriggerOrigin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class PipelineBuildRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 6657589502599019811L;
    @NotNull
    private Long pipelineId;
    @NotNull
    @Size(min = 64, max = 64)
    private String uuid;
    @NotNull
    private TriggerModel triggerModel;
    @NotNull
    private TriggerMatch triggerMatch;

    @NotNull
    private TriggerOrigin triggerOrigin;

}
