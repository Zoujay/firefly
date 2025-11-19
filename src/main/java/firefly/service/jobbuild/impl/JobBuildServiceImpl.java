package firefly.service.jobbuild.impl;

import firefly.bean.dto.JobBuildDto;
import firefly.bean.dto.StageBuildDto;
import firefly.bean.dto.StageConfigDto;
import firefly.constant.BuildStatus;
import firefly.dao.jobbuild.IJobBuildDao;
import firefly.model.job.JobBuild;
import firefly.service.jobbuild.IJobBuildService;
import firefly.service.stagebuild.IStageBuildService;
import firefly.service.stageconfig.IStageConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


@Service
@Transactional
public class JobBuildServiceImpl implements IJobBuildService {
    @Autowired
    private IJobBuildDao jobBuildDao;

    @Autowired
    private IStageBuildService stageBuildService;

    @Autowired
    private IStageConfigService stageConfig;

    @Override
    public Long saveJobBuild(JobBuildDto jobBuildDto) {
        JobBuild jobBuild = this.assembleJobBuild(jobBuildDto, BuildStatus.PENDING);
        jobBuildDao.save(jobBuild);
        if (jobBuild.getId() != null && jobBuild.getId() > 0) {
            return jobBuild.getId();
        }
        return -1L;
    }

    @Override
    public JobBuildDto getJobBuildByID(Long jobBuildID) {
        Optional<JobBuild> jobBuild = jobBuildDao.findById(jobBuildID);
        return jobBuild.map(this::assembleJobBuildDto).orElse(null);
    }

    @Override
    public Boolean updateJobBuildStatus(Long jobBuildID, BuildStatus status) {
        int result = jobBuildDao.updateJobBuildStatusByID(jobBuildID, status);
        return result == 1;
    }

    @Override
    public List<JobBuildDto> getJobBuildsByStageBuildID(Long stageBuildID) {
        StageBuildDto stageBuildDto = stageBuildService.getStageBuildByID(stageBuildID);
        if (stageBuildDto == null) {
            return List.of();
        }
        Long stageConfigID = stageBuildDto.getStageConfigID();
        if(stageConfigID == null) {
            return List.of();
        }
        StageConfigDto stageConfigDto = stageConfig.getStageConfigByID(stageConfigID);
        boolean isJobParallel = stageConfigDto.getIsJobParallel();
        List<JobBuild> result =jobBuildDao.getJobBuildsByStageBuildID(stageBuildID);
        List<JobBuildDto> jobBuildDtos = new ArrayList<>();
        if(isJobParallel) {
            for(JobBuild jobBuild : result) {
                if(jobBuild.getJobStatus().name().equals(BuildStatus.PENDING.name())) {
                    jobBuildDtos.add(this.assembleJobBuildDto(jobBuild));
                }
            }
        }else {
            for(JobBuild jobBuild : result) {
                if(jobBuild.getJobStatus().name().equals(BuildStatus.PENDING.name())) {
                    jobBuildDtos.add(this.assembleJobBuildDto(jobBuild));
                    break;
                }
            }
        }
        return jobBuildDtos;
    }

    @Override
    public BuildStatus checkParallelStageStatus(List<JobBuildDto> jobBuildDtos) {
        int success = 0;
        int pending = 0;
        for(JobBuildDto jobBuildDto : jobBuildDtos) {
            String status = jobBuildDto.getStatus().name();
            if(status.equals(BuildStatus.FAILURE.name())) {
                return BuildStatus.FAILURE;
            }
            if (status.equals(BuildStatus.SUCCESS.name())) {
                success++;
            }
            if (status.equals(BuildStatus.PENDING.name())) {
                pending++;
            }
        }
        if (success == jobBuildDtos.size()) {
            return BuildStatus.SUCCESS;
        }
        if (pending == jobBuildDtos.size()) {
            return BuildStatus.PENDING;
        }
        return BuildStatus.RUNNING;
    }

    @Override
    public BuildStatus checkSerializeStageStatus(JobBuildDto jobBuildDto) {

        return null;
    }

    private JobBuild assembleJobBuild(JobBuildDto jobBuildDto, BuildStatus status) {
        JobBuild jobBuild = new JobBuild();
        jobBuild.setStageBuildID(jobBuildDto.getStageBuildID())
                .setJobID(jobBuildDto.getJobConfigID())
                .setJobStatus(status);
        return jobBuild;
    }

    private JobBuildDto assembleJobBuildDto(JobBuild jobBuild) {
        JobBuildDto dto = new JobBuildDto();
        dto.setStageBuildID(jobBuild.getStageBuildID())
                .setJobConfigID(jobBuild.getJobID())
                .setJobBuildID(jobBuild.getId())
                .setStatus(jobBuild.getJobStatus());
        return dto;
    }
}
