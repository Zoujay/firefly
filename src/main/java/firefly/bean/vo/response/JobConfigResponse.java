package firefly.bean.vo.response;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class JobConfigResponse {

    private Long id;
    private Long stageID;
    private String uuid;
    private String name;
    private String pluginType;
    private Long pluginID;
    private JsonNode pluginRaw;

}
