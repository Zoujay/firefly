package firefly.service.jobconfig;

import firefly.bean.dto.JobConfigDto;
import firefly.bean.vo.request.JobConfigRequest;
import firefly.bean.vo.response.JobConfigResponse;
import firefly.model.job.JobModel;

import java.util.List;

public interface IJobConfigService {
    JobConfigDto createJobConfig(JobConfigRequest jobConfigRequest, Long stageID, Long pluginID);

    JobConfigDto getJobConfigByID(Long jobID);
    List<JobConfigDto> getJobConfigsByStageID(Long stageID);

    JobConfigDto getJobConfigByUUID(String jobUUID);

    JobModel assembleJobModel(JobConfigRequest request, Long stageID, Long pluginID);
    JobConfigDto assembleJobConfigDto(JobModel jobModel);

    JobConfigResponse assembleJobConfigResponse(JobConfigDto jobConfigDto);
}
