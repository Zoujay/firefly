package firefly.bean.dto;

import firefly.constant.PluginType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class JobConfigDto {
    private Long id;
    private Long stageID;
    private String uuid;
    private String name;
    private PluginType pluginType;
    private Long pluginID;
    private AbstractPluginDto pluginRaw;
}
