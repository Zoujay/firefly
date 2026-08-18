package firefly.bean.dto;

import java.io.Serial;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@EqualsAndHashCode(callSuper = true)
@ToString
@Data
public class TextPluginConfigDto extends AbstractPluginDto implements Serializable {
  @Serial private static final long serialVersionUID = -195154578987742967L;
  private String text;
  private Long jobConfigID;
}
