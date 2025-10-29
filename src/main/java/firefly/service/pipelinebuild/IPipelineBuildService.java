package firefly.service.pipelinebuild;

import firefly.bean.dto.PipelineBuildDto;
import firefly.bean.dto.message.BaseMessage;
import firefly.bean.vo.request.PipelineBuildRequest;
import firefly.constant.BuildStatus;

public interface IPipelineBuildService {

    Boolean updatePipelineBuildStatus(Long pipelineBuildID, BuildStatus status);

    Long savePipelineBuild(PipelineBuildDto pipelineBuildDto);

    PipelineBuildDto getPipelineBuild(Long pipelineBuildID);

    PipelineBuildDto parsePipelineBuildRequest(PipelineBuildRequest pipelineBuildRequest);

    Long triggerPipeline(PipelineBuildRequest pipelineBuildRequest);


    Long buildPipeline(PipelineBuildDto pipelineBuildDto);

    BaseMessage buildMessage(PipelineBuildDto pipelineBuildDto, Long pipelineBuildID);



}
