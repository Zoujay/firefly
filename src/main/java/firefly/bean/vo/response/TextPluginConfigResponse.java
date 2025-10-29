package firefly.bean.vo.response;

import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class TextPluginConfigResponse extends AbstractPluginConfigResponse{
    private String text;
}
