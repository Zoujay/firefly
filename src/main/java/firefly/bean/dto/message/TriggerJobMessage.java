package firefly.bean.dto.message;

import firefly.constant.BuildStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;


@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class TriggerJobMessage implements Serializable {
    @Serial
    private static final long serialVersionUID = 1765961541468589051L;

    private String messageUUID;

    private Long jobBuildID;

    private BuildStatus buildStatus;

}
