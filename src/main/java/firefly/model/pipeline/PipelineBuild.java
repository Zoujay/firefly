package firefly.model.pipeline;

import firefly.constant.BuildStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;


@Data
@NoArgsConstructor
@Entity
@Table(name = "pipeline_build")
@Accessors(chain = true)
public class PipelineBuild {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    @Column(name = "pipeline_id")
    private Long pipelineID;

    @Enumerated(EnumType.STRING)
    @Column(name = "pipeline_status")
    private BuildStatus pipelineStatus;

}
