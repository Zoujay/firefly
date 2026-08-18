package firefly.service.pluginconfig;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import firefly.bean.dto.TextPluginConfigDto;
import firefly.model.plugin.TextPluginModel;
import firefly.service.pluginconfig.impl.TextPluginConfigImpl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TextPluginConfigImplTests {

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private TextPluginConfigImpl textPluginConfig;

    @Test
    void assembleTextPluginConfigUsesJobConfigId() {
        TextPluginModel model =
            new TextPluginModel().setId(10L).setJobConfigID(20L).setText("test plugin");

        TextPluginConfigDto result = textPluginConfig.assembleTextPluginConfigDto(model);
        JsonNode json = new ObjectMapper().valueToTree(result);

        assertAll(
            () -> assertEquals(20L, result.getJobConfigID()),
            () -> assertEquals(20L, json.get("jobConfigID").longValue()),
            () -> assertTrue(json.has("jobConfigID")),
            () -> assertFalse(json.has("jobID")));
    }

    @Test
    void parsesPluginConfigWithTheApplicationObjectMapper() {
        JsonNode pluginRaw = objectMapper.createObjectNode().put("text", "hello");

        TextPluginConfigDto result = textPluginConfig.parseJobConfigRequest(pluginRaw, 20L);

        assertEquals("hello", result.getText());
        assertEquals(20L, result.getJobConfigID());
    }
}
