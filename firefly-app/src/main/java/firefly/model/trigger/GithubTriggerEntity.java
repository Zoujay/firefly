package firefly.model.trigger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@EqualsAndHashCode(callSuper = true)
@Data
@Accessors(chain = true)
@Entity
@Table(name = "github_trigger")
public class GithubTriggerEntity extends BaseTriggerEntity {

    @Column(name = "github_repo_url")
    private String githubRepoURL;

}
