package firefly.model.trigger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@EqualsAndHashCode(callSuper = true)
@Data
@Accessors(chain = true)
@Entity
@Table(name = "volcano_trigger")
public class VolcanoTriggerEntity extends BaseTriggerEntity {

  @Column(name = "pipeline_id")
  private Long pipelineID;

  @Column(name = "ak")
  private String ak;

  @Column(name = "sk")
  private String sk;
}
