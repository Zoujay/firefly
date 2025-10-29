package firefly.controller.pipelinebuild;

import firefly.bean.vo.request.PipelineBuildRequest;
import firefly.service.pipelinebuild.IPipelineBuildService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PipelineBuildController {

    @Autowired
    private IPipelineBuildService pipelineBuildService;

    @RequestMapping(value = "/manual_trigger/pipeline", method = RequestMethod.POST)
    public Long TriggerPipeline(@RequestBody PipelineBuildRequest pipelineBuildRequest) {
        return pipelineBuildService.triggerPipeline(pipelineBuildRequest);
    }

}
