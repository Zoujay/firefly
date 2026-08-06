package firefly.service.trigger.impl;

import firefly.bean.dto.message.GithubMessageEntity;
import firefly.constant.TriggerOrigin;
import firefly.dao.triggermessage.IGithubTriggerDao;
import firefly.model.trigger.GithubTriggerEntity;
import firefly.service.trigger.AbstractTrigger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class GithubTrigger extends AbstractTrigger<
        GithubTriggerEntity,
        GithubMessageEntity> {

    @Autowired
    private IGithubTriggerDao githubTriggerDao;

    @Override
    public TriggerOrigin getTriggerOrigin() {
        return TriggerOrigin.GITHUB;
    }

    @Override
    public Class<GithubMessageEntity> getMessageType() {
        return GithubMessageEntity.class;
    }

    @Override
    protected GithubTriggerEntity saveRealTrigger(
            GithubMessageEntity message
    ) {
        GithubTriggerEntity triggerEntity = new GithubTriggerEntity();
        triggerEntity.setGithubRepoURL(message.getURL());
        return githubTriggerDao.save(triggerEntity);
    }
}
