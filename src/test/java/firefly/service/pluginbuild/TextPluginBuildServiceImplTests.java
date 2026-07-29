package firefly.service.pluginbuild;

import firefly.dao.pluginbuild.ITextPluginBuildDao;
import firefly.service.pluginbuild.impl.TextPluginBuildServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TextPluginBuildServiceImplTests {

    @Mock
    private ITextPluginBuildDao textPluginBuildDao;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private TextPluginBuildServiceImpl textPluginBuildService;

    @Test
    void returnsMappedJobBuildID() {
        when(textPluginBuildDao.getJobBuildIDByPluginBuildID(70L)).thenReturn(50L);

        assertEquals(50L, textPluginBuildService.getJobBuildID(70L));
    }

    @Test
    void rejectsMissingJobBuildMapping() {
        when(textPluginBuildDao.getJobBuildIDByPluginBuildID(70L)).thenReturn(null);

        assertThrows(
                IllegalStateException.class,
                () -> textPluginBuildService.getJobBuildID(70L)
        );
    }
}
