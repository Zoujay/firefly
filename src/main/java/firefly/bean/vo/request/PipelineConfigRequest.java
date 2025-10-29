package firefly.bean.vo.request;


import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class PipelineConfigRequest implements Serializable {
    @Serial
    private static final long serialVersionUID = 6657589502599019811L;
    @NotNull
    @Size(min = 64, max = 64)
    private String uuid;
    @NotNull
    @Size(min = 10, max = 64)
    private String name;
    @NotNull
    private TriggerModel triggerModel;
    @NotNull
    private TriggerMatch triggerMatch;
    @NotNull
    private TriggerOrigin triggerOrigin;
    @NotNull
    private JsonNode originInfo;
    @NotNull
    private List<StageConfigRequest> stageConfigs;
}
