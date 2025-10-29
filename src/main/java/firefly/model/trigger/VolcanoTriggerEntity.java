package firefly.model.trigger;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;


@EqualsAndHashCode(callSuper = true)
@Data
@Accessors(chain = true)
@Entity
@Table(name = "volcano_trigger")
public class VolcanoTriggerEntity extends BaseTriggerEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pipeline_id")
    private Long pipelineID;

    @Column(name = "ak")
    private String ak;

    @Column(name = "sk")
    private String sk;

}
