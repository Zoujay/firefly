package firefly.service.pipelinebuild;

import firefly.bean.dto.PipelineBuildDto;
import firefly.bean.dto.message.BaseMessage;
import firefly.bean.vo.request.PipelineBuildRequest;
import firefly.bean.vo.response.PipelineRetryResponse;
import firefly.constant.BuildStatus;

public interface IPipelineBuildService {

  Boolean updatePipelineBuildStatus(
      Long pipelineBuildID, BuildStatus status, Integer executionAttempt);

  Long savePipelineBuild(PipelineBuildDto pipelineBuildDto);

  PipelineBuildDto getPipelineBuild(Long pipelineBuildID);

  PipelineBuildDto parsePipelineBuildRequest(PipelineBuildRequest pipelineBuildRequest);

  Long triggerPipeline(PipelineBuildRequest pipelineBuildRequest);

  PipelineRetryResponse retryPipeline(Long pipelineBuildID);

  Long buildPipeline(PipelineBuildDto pipelineBuildDto);

  BaseMessage buildMessage(PipelineBuildDto pipelineBuildDto, Long pipelineBuildID);
}
