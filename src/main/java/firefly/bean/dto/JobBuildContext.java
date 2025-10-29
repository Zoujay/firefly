package firefly.bean.dto;

import firefly.constant.BuildStatus;
import firefly.constant.PluginType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class JobBuildContext {

    private Long jobConfigID;
    private Long jobBuildID;
    private Long pluginID;
    private PluginType pluginType;
    private BuildStatus status;

}
