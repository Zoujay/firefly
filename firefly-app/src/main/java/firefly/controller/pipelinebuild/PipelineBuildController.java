package firefly.controller.pipelinebuild;

import firefly.bean.vo.request.PipelineBuildRequest;
import firefly.bean.vo.response.PipelineRetryResponse;
import firefly.service.pipelinebuild.IPipelineBuildService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PipelineBuildController {

  @Autowired private IPipelineBuildService pipelineBuildService;

  @RequestMapping(value = "/manual_trigger/pipeline", method = RequestMethod.POST)
  public Long TriggerPipeline(@Valid @RequestBody PipelineBuildRequest pipelineBuildRequest) {
    return pipelineBuildService.triggerPipeline(pipelineBuildRequest);
  }

  @PostMapping("/pipeline-builds/{pipelineBuildID}/retry")
  public PipelineRetryResponse retryPipeline(@PathVariable Long pipelineBuildID) {
    return pipelineBuildService.retryPipeline(pipelineBuildID);
  }
}
