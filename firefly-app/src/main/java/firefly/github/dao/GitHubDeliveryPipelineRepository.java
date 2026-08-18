package firefly.github.dao;

import firefly.github.model.GitHubDeliveryPipelineEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GitHubDeliveryPipelineRepository
    extends JpaRepository<GitHubDeliveryPipelineEntity, Long> {

  Optional<GitHubDeliveryPipelineEntity> findByDeliveryIdAndPipelineId(
      String deliveryId, Long pipelineId);

  List<GitHubDeliveryPipelineEntity> findAllByDeliveryId(String deliveryId);
}
