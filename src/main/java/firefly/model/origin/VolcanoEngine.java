package firefly.model.origin;


import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@Entity
@Table(name = "volcano_engine")
@Accessors(chain = true)
public class VolcanoEngine {

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
