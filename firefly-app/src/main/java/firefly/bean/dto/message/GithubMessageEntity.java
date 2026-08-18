package firefly.bean.dto.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Instant;

@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class GithubMessageEntity extends BaseMessage {
    private String deliveryId;
    private String eventType;
    private String action;
    private Long repositoryId;
    private String repositoryFullName;
    private String repositoryUrl;
    private String cloneUrl;
    private String sourceBranch;
    private String targetBranch;
    private String matchBranch;
    private String headSha;
    private Long senderId;
    private String senderLogin;
    private Instant receivedAt;
}
