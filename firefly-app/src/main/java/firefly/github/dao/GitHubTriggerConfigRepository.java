package firefly.github.dao;

import firefly.github.model.GitHubTriggerConfigEntity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GitHubTriggerConfigRepository
    extends JpaRepository<GitHubTriggerConfigEntity, Long> {

    Optional<GitHubTriggerConfigEntity> findByPipelineId(Long pipelineId);

    List<GitHubTriggerConfigEntity> findAllBySubscriptionId(Long subscriptionId);

    List<GitHubTriggerConfigEntity> findAllBySubscriptionIdAndEnabledTrue(Long subscriptionId);
}
