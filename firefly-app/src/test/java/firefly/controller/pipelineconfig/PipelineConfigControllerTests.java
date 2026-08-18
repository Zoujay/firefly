package firefly.controller.pipelineconfig;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import firefly.service.pipelineconfig.IPipelineConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
class PipelineConfigControllerTests {

  private MockMvc mockMvc;

  @Mock private IPipelineConfigService pipelineConfigService;

  @InjectMocks private PipelineConfigController pipelineConfigController;

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mockMvc =
        MockMvcBuilders.standaloneSetup(pipelineConfigController).setValidator(validator).build();
  }

  @Test
  void createPipelineRejectsShortPipelineUuid() throws Exception {
    mockMvc
        .perform(
            post("/create/pipeline")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "uuid": "test-pipeline001",
                      "name": "test-pipeline001",
                      "triggerModel": "MANUAL",
                      "triggerMatch": "ACCURATE",
                      "triggerOrigin": "VOLCANO",
                      "originInfo": {},
                      "stageConfigs": []
                    }
                    """))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(pipelineConfigService);
  }

  @Test
  void createPipelineCascadesValidationToStages() throws Exception {
    mockMvc
        .perform(
            post("/create/pipeline")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "uuid": "%s",
                      "name": "test-pipeline",
                      "triggerModel": "MANUAL",
                      "triggerMatch": "ACCURATE",
                      "triggerOrigin": "VOLCANO",
                      "originInfo": {},
                      "stageConfigs": [
                        {
                          "uuid": "short-stage-uuid",
                          "name": "stage-name",
                          "jobConfigs": []
                        }
                      ]
                    }
                    """
                        .formatted("p".repeat(64))))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(pipelineConfigService);
  }
}
