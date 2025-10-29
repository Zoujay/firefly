package firefly.service.pluginconfig;

import com.fasterxml.jackson.databind.JsonNode;
import firefly.bean.dto.AbstractPluginDto;
import firefly.constant.PluginType;

public interface IPluginConfig {
    PluginType getPluginType();

    Long savePlugin(JsonNode pluginRaw, Long jobID);

    AbstractPluginDto getPlugin(Long id);
}
