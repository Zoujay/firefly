package firefly.github.dao;

import firefly.github.model.GitHubDeliveryPipelineEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GitHubDeliveryPipelineRepository
        extends JpaRepository<GitHubDeliveryPipelineEntity, Long> {

    Optional<GitHubDeliveryPipelineEntity> findByDeliveryIdAndPipelineId(
            String deliveryId,
            Long pipelineId
    );

    List<GitHubDeliveryPipelineEntity> findAllByDeliveryId(String deliveryId);
}
