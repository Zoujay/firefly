package firefly.model.pipeline;


import firefly.constant.TriggerMatch;
import firefly.constant.TriggerModel;
import firefly.constant.TriggerOrigin;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;


@Data
@NoArgsConstructor
@Entity
@Table(name = "pipeline_config")
@Accessors(chain = true)
public class PipelineModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pipeline_uuid")
    private String pipelineUUID;

    @Column(name = "pipeline_name")
    private String pipelineName;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_mode")
    private TriggerModel triggerMode;


    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_match")
    private TriggerMatch triggerMatch;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_origin")
    private TriggerOrigin triggerOrigin;

    @Column(name = "origin_id")
    private Long originID = -1L;

}
