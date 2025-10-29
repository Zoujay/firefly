package firefly.service.pluginbuild;

import firefly.bean.dto.JobBuildContext;
import firefly.bean.dto.message.TriggerPluginMessage;
import firefly.constant.BuildStatus;
import firefly.constant.PluginType;

public interface IPluginBuild {

    PluginType getPluginType();
    Long getJobBuild(Long pluginBuildID);
    Long savePluginBuild(JobBuildContext jobBuildContext);
    Boolean executePluginBuild(Long id, BuildStatus status);
    Boolean updatePluginBuild(Long id, BuildStatus status);
    TriggerPluginMessage triggerPluginBuild(Long pluginBuildID, BuildStatus status);
}
