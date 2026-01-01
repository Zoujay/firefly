package firefly.service.jobbuild;


import firefly.bean.dto.JobBuildDto;
import firefly.constant.BuildStatus;

import java.util.List;

public interface IJobBuildService{
    Long saveJobBuild(JobBuildDto jobBuild);
    JobBuildDto getJobBuildByID(Long jobBuildID);
    Boolean updateJobBuildStatus(Long jobBuildID, BuildStatus status);
    JobBuildDto getJobBuildByJobConfigID(Long jobConfigID);
    List<JobBuildDto> getHeadJobBuildsByStageBuildID(Long stageConfigID, Long stageBuildID);
    List<JobBuildDto> getTailJobBuildsByStageBuildID(Long stageConfigID, Long stageBuildID);
    BuildStatus calculateStageStatus(List<JobBuildDto> jobBuildDtos);
}