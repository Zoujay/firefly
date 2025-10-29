package firefly.model.stage;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;


@Data
@NoArgsConstructor
@Entity
@Table(name = "stage_config")
@Accessors(chain = true)
public class StageModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pipeline_id")
    private Long pipeline_id = 0L;

    @Column(name = "stage_uuid")
    private String stageUUID;

    @Column(name = "stage_name")
    private String stageName;

    @Column(name = "is_job_parallel")
    private Boolean isJobParallel = true;

}
