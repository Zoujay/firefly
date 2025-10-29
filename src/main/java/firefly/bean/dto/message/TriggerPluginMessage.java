package firefly.bean.dto.message;


import firefly.constant.BuildStatus;
import firefly.constant.PluginType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class TriggerPluginMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = -4709514640861674239L;

    private String messageUUID;

    private PluginType pluginType;

    private Long pluginBuildID;

    private BuildStatus status;
}
