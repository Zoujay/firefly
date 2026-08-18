package firefly.service.pluginbuild.impl;

import static firefly.constant.KafkaConfiguration.PLUGIN_TOPIC;

import firefly.bean.dto.JobBuildContext;
import firefly.bean.dto.message.TriggerPluginMessage;
import firefly.constant.BuildStatus;
import firefly.constant.PluginType;
import firefly.dao.pluginbuild.ITextPluginBuildDao;
import firefly.model.plugin.TextPluginBuild;
import firefly.service.messagecenter.BusinessMessageUUID;
import firefly.service.outbox.OutboxService;
import firefly.service.pluginbuild.IPluginBuild;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
public class TextPluginBuildServiceImpl implements IPluginBuild {

  @Autowired private ITextPluginBuildDao textPluginBuildDao;

  @Autowired private OutboxService outboxService;

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
    if (jobBuildID == null || jobBuildID <= 0L) {
      throw new IllegalStateException("Job build not found for pluginBuildID=" + pluginBuildID);
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
    log.error("Failed to save text plugin build: jobBuildID={}", pluginDto.getJobBuildID());
    return -1L;
  }

  @Override
  public Boolean executePluginBuild(Long id, BuildStatus status, Integer executionAttempt) {
    Integer result = textPluginBuildDao.updatePluginBuildStatus(id, status, executionAttempt);
    if (result == null || result != 1) {
      throw new IllegalStateException(
          "Failed to start plugin build: pluginBuildID="
              + id
              + ", executionAttempt="
              + executionAttempt);
    }
    // execute
    log.info(
        "Executing mock text plugin build: pluginBuildID={}, executionAttempt={}",
        id,
        executionAttempt);
    TriggerPluginMessage triggerPluginMessage =
        this.triggerPluginBuild(id, BuildStatus.SUCCESS, executionAttempt);
    outboxService.enqueue(PLUGIN_TOPIC, triggerPluginMessage);
    return true;
  }

  @Override
  public Boolean updatePluginBuild(Long id, BuildStatus status, Integer executionAttempt) {
    Integer result = textPluginBuildDao.updatePluginBuildStatus(id, status, executionAttempt);
    if (result != null) {
      return result == 1;
    }
    // execute
    log.warn(
        "Failed to update text plugin build status: pluginBuildID={}, status={},"
            + " executionAttempt={}",
        id,
        status,
        executionAttempt);
    return false;
  }

  @Override
  public TriggerPluginMessage triggerPluginBuild(
      Long pluginBuildID, BuildStatus status, Integer executionAttempt) {
    TriggerPluginMessage triggerPluginMessage = new TriggerPluginMessage();
    triggerPluginMessage
        .setPluginType(PluginType.TEXT)
        .setMessageUUID(
            BusinessMessageUUID.plugin(PluginType.TEXT, pluginBuildID, executionAttempt, status))
        .setPluginBuildID(pluginBuildID)
        .setExecutionAttempt(executionAttempt)
        .setStatus(status);
    return triggerPluginMessage;
  }

  private TextPluginBuild assembleTextPluginBuild(JobBuildContext jobBuildContext) {
    TextPluginBuild pluginBuild = new TextPluginBuild();
    pluginBuild
        .setTextPluginStatus(jobBuildContext.getStatus())
        .setPluginID(jobBuildContext.getPluginID())
        .setExecutionAttempt(jobBuildContext.getExecutionAttempt())
        .setJobBuildID(jobBuildContext.getJobBuildID());
    return pluginBuild;
  }
}
