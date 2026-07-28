package firefly.service.pluginconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import firefly.bean.dto.TextPluginConfigDto;
import firefly.model.plugin.TextPluginModel;
import firefly.service.pluginconfig.impl.TextPluginConfigImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextPluginConfigImplTests {

    private final TextPluginConfigImpl textPluginConfig = new TextPluginConfigImpl();

    @Test
    void assembleTextPluginConfigUsesJobConfigId() {
        TextPluginModel model = new TextPluginModel()
                .setId(10L)
                .setJobConfigID(20L)
                .setText("test plugin");

        TextPluginConfigDto result = textPluginConfig.assembleTextPluginConfigDto(model);
        JsonNode json = new ObjectMapper().valueToTree(result);

        assertAll(
                () -> assertEquals(20L, result.getJobConfigID()),
                () -> assertEquals(20L, json.get("jobConfigID").longValue()),
                () -> assertTrue(json.has("jobConfigID")),
                () -> assertFalse(json.has("jobID"))
        );
    }
}
