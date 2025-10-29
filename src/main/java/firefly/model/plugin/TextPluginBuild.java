package firefly.model.plugin;


import firefly.constant.BuildStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
@Entity
@Table(name = "text_plugin_build")
@Accessors(chain = true)
public class TextPluginBuild extends BasePluginBuild {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plugin_id")
    private Long pluginID;

    @Column(name = "job_build_id")
    private Long jobBuildID;

    @Enumerated(EnumType.STRING)
    @Column(name = "text_plugin_status")
    private BuildStatus textPluginStatus;
}
