package firefly.model.stage;


import firefly.constant.BuildStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@Entity
@Table(name = "stage_build")
@Accessors(chain = true)
public class StageBuild {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pipeline_build_id")
    private Long pipelineBuildID = 0L;

    @Column(name = "stage_id")
    private Long stageID;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage_status")
    private BuildStatus stageStatus;
}
