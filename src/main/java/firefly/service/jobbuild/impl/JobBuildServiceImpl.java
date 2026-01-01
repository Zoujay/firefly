package firefly.service.jobbuild.impl;

import firefly.bean.dto.JobBuildDto;
import firefly.bean.dto.JobRelationDto;
import firefly.bean.dto.StageBuildDto;
import firefly.bean.dto.StageConfigDto;
import firefly.constant.BuildStatus;
import firefly.dao.jobbuild.IJobBuildDao;
import firefly.dao.jobconfig.IJobRelationDao;
import firefly.model.job.JobBuild;
import firefly.service.jobbuild.IJobBuildService;
import firefly.service.jobconfig.IJobRelationService;
import firefly.service.stagebuild.IStageBuildService;
import firefly.service.stageconfig.IStageConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


@Service
@Transactional
public class JobBuildServiceImpl implements IJobBuildService {
    @Autowired
    private IJobBuildDao jobBuildDao;

    @Autowired
    private IStageBuildService stageBuildService;

    @Autowired
    private IJobRelationService jobRelationService;

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
    public JobBuildDto getJobBuildByJobConfigID(Long jobConfigID) {
        Optional<JobBuild> jobBuildOptional = jobBuildDao.getJobBuildByJobConfigID(jobConfigID);
        return jobBuildOptional.map(this::assembleJobBuildDto).orElse(null);
    }



    @Override
    public List<JobBuildDto> getHeadJobBuildsByStageBuildID(Long stageConfigID, Long stageBuildID) {
        List<JobBuildDto> jobBuildDtos = new ArrayList<>();
        List<JobRelationDto> jobRelationDtos = jobRelationService.getAllHeadJobRelationByStageID(stageConfigID);
        List<JobBuild> jobBuilds = jobBuildDao.getJobBuildsByStageBuildID(stageBuildID);
        for(JobBuild build : jobBuilds) {
            for(JobRelationDto jobRelationDto : jobRelationDtos) {
                if(Objects.equals(jobRelationDto.getJobID(), build.getJobID())) {
                    JobBuildDto jobBuildDto = this.assembleJobBuildDto(build);
                    jobBuildDtos.add(jobBuildDto);
                }
            }
        }
        return jobBuildDtos;
    }

    @Override
    public List<JobBuildDto> getTailJobBuildsByStageBuildID(Long stageConfigID, Long stageBuildID) {
        List<JobBuildDto> jobBuildDtos = new ArrayList<>();
        List<JobRelationDto> jobRelationDtos = jobRelationService.getAllTailJobRelationByStageID(stageConfigID);
        List<JobBuild> jobBuilds = jobBuildDao.getJobBuildsByStageBuildID(stageBuildID);
        for(JobBuild build : jobBuilds) {
            for(JobRelationDto jobRelationDto : jobRelationDtos) {
                if(Objects.equals(jobRelationDto.getJobID(), build.getJobID())) {
                    JobBuildDto jobBuildDto = this.assembleJobBuildDto(build);
                    jobBuildDtos.add(jobBuildDto);
                }
            }
        }
        return jobBuildDtos;
    }

    @Override
    public BuildStatus calculateStageStatus(List<JobBuildDto> jobBuildDtos) {
        int running = 0;
        int pending = 0;
        int failure = 0;
        int success = 0;
        for(JobBuildDto jobBuildDto : jobBuildDtos) {
            switch (jobBuildDto.getStatus()) {
                case SUCCESS -> success++;
                case FAILURE -> failure++;
                case PENDING -> pending++;
                case RUNNING -> running++;
            }
        }
        int len = jobBuildDtos.size();
        if(failure >= 1){
            return BuildStatus.FAILURE;
        }
        if(len == success) {
            return BuildStatus.SUCCESS;
        }
        if(len == pending) {
            return BuildStatus.PENDING;
        }
        return BuildStatus.RUNNING;
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
