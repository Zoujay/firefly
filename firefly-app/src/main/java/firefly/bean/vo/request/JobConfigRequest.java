package firefly.bean.vo.request;

import com.fasterxml.jackson.databind.JsonNode;
import firefly.constant.PluginType;
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
public class JobConfigRequest implements Serializable {
    @Serial
    private static final long serialVersionUID = -51357335357194653L;
    @NotNull
    @Size(min = 64, max = 64)
    private String uuid;
    @NotNull
    @Size(min = 10, max = 64)
    private String name;
    @NotNull
    private PluginType pluginType;
    @NotNull
    private JsonNode pluginRaw;

}
