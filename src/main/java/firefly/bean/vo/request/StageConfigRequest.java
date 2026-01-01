package firefly.bean.vo.request;


import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class StageConfigRequest implements Serializable {
    @Serial
    private static final long serialVersionUID = 1318158863443930509L;
    @NotNull
    @Size(min = 64, max = 64)
    private String uuid;
    @NotNull
    @Size(min = 10, max = 64)
    private String name;
    @NotNull
    private List<List<JobConfigRequest>> jobConfigs;
}
