package firefly.bean.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class GithubTriggerConfigDto extends BaseTriggerOriginDto {
    private String subscriptionId;
    private List<String> events;
    private List<String> pullRequestActions;
    private Boolean ignoreDeletePush = true;
}
