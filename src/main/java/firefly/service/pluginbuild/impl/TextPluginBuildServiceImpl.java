package firefly.service.pluginbuild.impl;

import firefly.bean.dto.JobBuildContext;
import firefly.bean.dto.message.TriggerPluginMessage;
import firefly.constant.BuildStatus;
import firefly.constant.PluginType;
import firefly.dao.pluginbuild.ITextPluginBuildDao;
import firefly.model.plugin.TextPluginBuild;
import firefly.service.pluginbuild.IPluginBuild;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static firefly.constant.KafkaConfiguration.PLUGIN_TOPIC;

@Service
@Transactional
public class TextPluginBuildServiceImpl implements IPluginBuild {

    @Autowired
    private ITextPluginBuildDao textPluginBuildDao;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Override
    public PluginType getPluginType() {
        return PluginType.TEXT;
    }

    @Override
    public Long getPluginBuildIDByJobBuildID(Long jobBuildID) {
        return textPluginBuildDao.getPluginBuildIDByJobBuildID(jobBuildID);
    }

    @Override
    public Long getJobBuildID(Long pluginBuildID) {
        Long jobBuildID = textPluginBuildDao.getJobBuildIDByPluginBuildID(pluginBuildID);
        if(jobBuildID == null) {
            return -1L;
        }
        return jobBuildID;
    }

    @Override
    public Long savePluginBuild(JobBuildContext pluginDto) {
        TextPluginBuild pluginBuild = this.assembleTextPluginBuild(pluginDto);
        textPluginBuildDao.save(pluginBuild);
        Long id = pluginBuild.getId();
        if (id != null && id > 0) {
            return id;
        }
        System.out.println("mock trigger plugin build");
        return -1L;
    }

    @Override
    public Boolean executePluginBuild(Long id, BuildStatus status) {
        Integer result = textPluginBuildDao.updatePluginBuildStatus(id, status);
        // execute
        System.out.println("mock trigger plugin build");
        TriggerPluginMessage triggerPluginMessage = this.triggerPluginBuild(id, BuildStatus.SUCCESS);
        kafkaTemplate.send(PLUGIN_TOPIC, triggerPluginMessage);
        return true;
    }

    @Override
    public Boolean updatePluginBuild(Long id, BuildStatus status) {
        Integer result = textPluginBuildDao.updatePluginBuildStatus(id, status);
        if (result != null) {
            return result == 1;
        }
        // execute
        System.out.println("mock trigger plugin build");
        return false;
    }

    @Override
    public TriggerPluginMessage triggerPluginBuild(Long pluginBuildID, BuildStatus status) {
        UUID uuid = UUID.randomUUID();
        TriggerPluginMessage triggerPluginMessage = new TriggerPluginMessage();
        triggerPluginMessage.setPluginType(PluginType.TEXT)
                .setMessageUUID(uuid.toString())
                .setPluginBuildID(pluginBuildID)
                .setStatus(status);
        return triggerPluginMessage;
    }


    private TextPluginBuild assembleTextPluginBuild(JobBuildContext jobBuildContext) {
        TextPluginBuild pluginBuild = new TextPluginBuild();
        pluginBuild.setTextPluginStatus(jobBuildContext.getStatus())
                .setPluginID(jobBuildContext.getPluginID())
                .setJobBuildID(jobBuildContext.getJobBuildID());
        return pluginBuild;
    }


}
