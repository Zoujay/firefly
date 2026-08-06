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
@Table(name = "job_config")
@Accessors(chain = true)
public class JobModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_uuid")
    private String jobUUID;

    @Column(name = "job_name")
    private String jobName;

    @Column(name = "stage_id")
    private Long stageID;


    @Column(name = "plugin_type")
    private String pluginType;

    @Column(name = "plugin_id")
    private Long pluginID = 0L;

    @Column(name = "plugin_raw")
    private String pluginRaw;

}


