package firefly.controller.pipelineconfig;

import firefly.bean.vo.request.PipelineConfigRequest;
import firefly.bean.vo.response.PipelineConfigResponse;
import firefly.service.pipelineconfig.IPipelineConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
public class PipelineConfigController {

    @Autowired
    private IPipelineConfig pipelineConfig;

    @RequestMapping(value = "/create/pipeline", method = RequestMethod.POST)
    public String CreatePipeline(@RequestBody PipelineConfigRequest pipelineConfigRequest){
        return pipelineConfig.createPipeline(pipelineConfigRequest);
    }


    @RequestMapping(value = "/pipeline", method = RequestMethod.GET)
    public PipelineConfigResponse GetPipeline(@RequestParam String uuid){
        return pipelineConfig.getPipelineConfigByUUID(uuid);
    }

}
