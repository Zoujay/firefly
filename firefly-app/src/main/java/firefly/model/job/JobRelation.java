package firefly.model.job;


import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "job_relation")
@Accessors(chain = true)
public class JobRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pipeline_id")
    private Long pipelineID;

    @Column(name = "stage_id")
    private Long stageID;

    @Column(name = "job_id")
    private Long jobID;

    @Column(name = "next_job_id")
    private Long nextJobID;

    @Column(name = "previous_job_id")
    private Long previousJobID;

    @Column(name = "is_head_job")
    private boolean isHeadJob;
}
