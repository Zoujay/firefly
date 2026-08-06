package firefly.service.triggerorigin;

import com.fasterxml.jackson.databind.JsonNode;
import firefly.bean.dto.BaseTriggerOriginDto;
import firefly.bean.dto.PipelineBuildDto;
import firefly.bean.dto.message.BaseMessage;
import firefly.constant.TriggerOrigin;

public interface ITriggerOrigin {

    TriggerOrigin getTriggerOrigin();

    BaseTriggerOriginDto parseTriggerOrigin(JsonNode triggerOrigin);

    BaseMessage buildMessage(PipelineBuildDto pipelineBuildDto, Long pipelineBuildID);

    Long saveTriggerOrigin(JsonNode triggerOrigin, Long pipelineID);
}
