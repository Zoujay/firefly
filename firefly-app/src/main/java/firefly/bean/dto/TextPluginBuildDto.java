package firefly.bean.dto;

import java.io.Serial;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@EqualsAndHashCode(callSuper = true)
@ToString
@Data
public class TextPluginBuildDto extends PluginBaseDto implements Serializable {

  @Serial private static final long serialVersionUID = 2779689409435933725L;
}
