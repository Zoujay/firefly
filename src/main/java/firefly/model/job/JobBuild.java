package firefly.model.job;


import firefly.constant.BuildStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "job_build")
@Accessors(chain = true)
public class JobBuild {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id")
    private Long jobID;

    @Column(name = "stage_build_id")
    private Long stageBuildID;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_status")
    private BuildStatus jobStatus;

}
