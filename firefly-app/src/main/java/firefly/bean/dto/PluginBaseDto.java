package firefly.bean.dto;

import firefly.constant.BuildStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.Accessors;


@ToString
@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
public class PluginBaseDto {
    private Long ID;
    private Long pluginID;
    private BuildStatus status;
    private Long jobBuildID;
}
