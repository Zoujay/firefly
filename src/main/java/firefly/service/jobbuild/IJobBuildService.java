package firefly.service.jobbuild;


import firefly.bean.dto.JobBuildDto;
import firefly.constant.BuildStatus;

import java.util.List;

public interface IJobBuildService{
    Long saveJobBuild(JobBuildDto jobBuild);
    JobBuildDto getJobBuildByID(Long jobBuildID);
    Boolean updateJobBuildStatus(Long jobBuildID, BuildStatus status);

    List<JobBuildDto> getJobBuildsByStageBuildStage(Long stageBuildID);
    BuildStatus checkParallelStageStatus(List<JobBuildDto> jobBuildDtos);
    BuildStatus checkSerializeStageStatus(JobBuildDto jobBuildDto);
}