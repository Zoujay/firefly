package firefly.service.pluginparser;

import firefly.constant.PluginType;
import firefly.service.pluginbuild.IPluginBuild;
import firefly.service.pluginconfig.IPluginConfig;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class PluginServiceParser implements InitializingBean {
    @Autowired
    private List<IPluginConfig> plugins;
    public static final Map<PluginType, IPluginConfig> PLUGIN_MAP = new HashMap<>();

    @Autowired
    private List<IPluginBuild> pluginBuilds;
    public static final Map<PluginType, IPluginBuild> PLUGIN_BUILD_MAP = new HashMap<>();


    @Override
    public void afterPropertiesSet() {
        plugins.forEach(plugin -> {
            PLUGIN_MAP.put(plugin.getPluginType(), plugin);
        });
        pluginBuilds.forEach(plugin -> {
            PLUGIN_BUILD_MAP.put(plugin.getPluginType(), plugin);
        });
    }
}
