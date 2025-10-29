package firefly.service.trigger.impl;

import firefly.bean.dto.message.BaseMessage;
import firefly.bean.dto.message.GithubMessageEntity;
import firefly.constant.TriggerOrigin;
import firefly.dao.triggermessage.IGithubTriggerDao;
import firefly.model.trigger.GithubTriggerEntity;
import firefly.service.trigger.AbstractTrigger;
import firefly.service.trigger.ITrigger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class GithubTrigger extends AbstractTrigger<GithubTriggerEntity, GithubMessageEntity>
        implements ITrigger<GithubTriggerEntity, GithubMessageEntity> {

    @Autowired
    private IGithubTriggerDao githubTriggerDao;

    @Override
    public TriggerOrigin getTriggerType() {
        return TriggerOrigin.GITHUB;
    }

    @Override
    public Long saveRealTrigger(BaseMessage triggerMessage) {
        GithubTriggerEntity githubTriggerEntity = this.assembleGithubTrigger((GithubMessageEntity) triggerMessage);
        githubTriggerDao.save(githubTriggerEntity);
        return githubTriggerEntity.getId();
    }

    @Override
    public BaseMessage parseMessage(String messageRaw) {
        return null;
    }

    private GithubTriggerEntity assembleGithubTrigger(GithubMessageEntity githubMessageEntity) {
        GithubTriggerEntity githubTriggerEntity = new GithubTriggerEntity();
        githubTriggerEntity.setGithubRepoURL(githubMessageEntity.getURL());
        return githubTriggerEntity;
    }

    @Override
    public void execute(BaseMessage message) {
        saveRealTrigger(message);
        // trigger pipeline

    }

    private GithubMessageEntity assembleGithubMessageEntity() {
        GithubMessageEntity githubMessageEntity = new GithubMessageEntity();
        return githubMessageEntity;
    }
}
