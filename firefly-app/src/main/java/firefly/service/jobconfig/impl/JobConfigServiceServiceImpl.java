package firefly.service.jobconfig.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import firefly.bean.dto.AbstractPluginDto;
import firefly.bean.dto.JobConfigDto;
import firefly.bean.vo.request.JobConfigRequest;
import firefly.bean.vo.response.JobConfigResponse;
import firefly.constant.PluginType;
import firefly.dao.jobconfig.IJobConfigDao;
import firefly.model.job.JobModel;
import firefly.service.jobconfig.IJobConfigService;
import firefly.service.pluginconfig.IPluginConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static firefly.service.pluginparser.PluginServiceParser.PLUGIN_MAP;

@Service
@Transactional
public class JobConfigServiceServiceImpl implements IJobConfigService {

    @Autowired
    private IJobConfigDao jobConfigDao;

    @Transactional
    @Override
    public JobConfigDto createJobConfig(JobConfigRequest jobConfigRequest, Long stageID, Long pluginID) {
        JobModel jobModel = this.assembleJobModel(jobConfigRequest, stageID, pluginID);
        jobConfigDao.save(jobModel);
        return this.assembleJobConfigDto(jobModel);
    }

    @Override
    public JobConfigDto getJobConfigByID(Long jobID) {
        Optional<JobModel> jobModel = jobConfigDao.findById(jobID);
        return jobModel.map(this::assembleJobConfigDto).orElse(null);
    }

    @Override
    public List<JobConfigDto> getJobConfigsByStageID(Long stageID) {
        List<JobModel> jobModels = jobConfigDao.getJobModelsByStageID(stageID);
        List<JobConfigDto> jobConfigDtos = new ArrayList<>();
        for (JobModel jobModel : jobModels) {
            JobConfigDto jobConfigDto = this.assembleJobConfigDto(jobModel);
            jobConfigDtos.add(jobConfigDto);
        }
        return jobConfigDtos;
    }

    @Override
    public JobConfigDto getJobConfigByUUID(String jobUUID) {
        Optional<JobModel> jobModel = jobConfigDao.getJobModelByUUID(jobUUID);
        return jobModel.map(this::assembleJobConfigDto).orElse(null);
    }

    @Override
    public JobModel assembleJobModel(JobConfigRequest request, Long stageID, Long pluginID) {
        JobModel model = new JobModel();
        model.setPluginType(request.getPluginType().name());
        model.setJobUUID(request.getUuid());
        model.setStageID(stageID);
        model.setPluginID(pluginID);
        model.setJobName(request.getName());
        model.setPluginRaw(request.getPluginRaw().toString());
        return model;
    }

    @Override
    public JobConfigDto assembleJobConfigDto(JobModel jobModel) {
        PluginType pluginType = PluginType.valueOf(jobModel.getPluginType());
        IPluginConfig pluginConfig = PLUGIN_MAP.get(pluginType);
        AbstractPluginDto dto = pluginConfig.getPlugin(jobModel.getPluginID());
        return new JobConfigDto(
                jobModel.getId(),
                jobModel.getStageID(),
                jobModel.getJobUUID(),
                jobModel.getJobName(),
                PluginType.valueOf(jobModel.getPluginType()),
                jobModel.getPluginID(),
                dto
        );
    }


    @Override
    public JobConfigResponse assembleJobConfigResponse(JobConfigDto jobConfigDto) {
        JsonNode node = new ObjectMapper().valueToTree(jobConfigDto.getPluginRaw());
        return new JobConfigResponse(
                jobConfigDto.getId(),
                jobConfigDto.getStageID(),
                jobConfigDto.getUuid(),
                jobConfigDto.getName(),
                jobConfigDto.getPluginType().name(),
                jobConfigDto.getPluginID(),
                node
        );
    }
}
