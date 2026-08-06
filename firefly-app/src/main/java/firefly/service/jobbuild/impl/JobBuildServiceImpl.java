package firefly.service.jobbuild.impl;

import firefly.bean.dto.JobBuildDto;
import firefly.bean.dto.JobRelationDto;
import firefly.constant.BuildStatus;
import firefly.dao.jobbuild.IJobBuildDao;
import firefly.model.job.JobBuild;
import firefly.service.jobbuild.IJobBuildService;
import firefly.service.jobconfig.IJobRelationService;
import firefly.service.stagebuild.IStageBuildService;
import firefly.service.stageconfig.IStageConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    public Boolean updateJobBuildStatus(
            Long jobBuildID,
            BuildStatus status,
            Integer executionAttempt
    ) {
        BuildStatus expectedStatus = status == BuildStatus.RUNNING
                ? BuildStatus.PENDING
                : BuildStatus.RUNNING;
        int result = jobBuildDao.transitionJobBuildStatus(
                jobBuildID,
                expectedStatus,
                status,
                executionAttempt
        );
        return result == 1;
    }

    @Override
    public JobBuildDto getJobBuildByJobConfigIDAndStageBuildID(Long jobConfigID, Long stageBuildID) {
        Optional<JobBuild> jobBuildOptional = jobBuildDao.getJobBuildByJobConfigIDAndStageBuildID(jobConfigID, stageBuildID);
        return jobBuildOptional.map(this::assembleJobBuildDto).orElse(null);
    }


    @Override
    public List<JobBuildDto> getHeadJobBuildsByStageBuildID(Long stageConfigID, Long stageBuildID) {
        List<JobBuildDto> jobBuildDtos = new ArrayList<>();
        List<JobRelationDto> jobRelationDtos = jobRelationService.getAllHeadJobRelationByStageID(stageConfigID);
        List<JobBuild> jobBuilds = jobBuildDao.getJobBuildsByStageBuildID(stageBuildID);
        for (JobBuild build : jobBuilds) {
            for (JobRelationDto jobRelationDto : jobRelationDtos) {
                if (Objects.equals(jobRelationDto.getJobID(), build.getJobID())) {
                    JobBuildDto jobBuildDto = this.assembleJobBuildDto(build);
                    jobBuildDtos.add(jobBuildDto);
                }
            }
        }
        return jobBuildDtos;
    }

    @Override
    public List<JobBuildDto> getRunnableJobBuildsByStageBuildID(
            Long stageConfigID,
            Long stageBuildID
    ) {
        List<JobBuild> jobBuilds = jobBuildDao.getJobBuildsByStageBuildID(stageBuildID);
        Map<Long, JobBuild> jobBuildByConfigID = new HashMap<>();
        for (JobBuild jobBuild : jobBuilds) {
            jobBuildByConfigID.put(jobBuild.getJobID(), jobBuild);
        }

        List<JobBuildDto> runnableJobs = new ArrayList<>();
        List<JobRelationDto> headRelations =
                jobRelationService.getAllHeadJobRelationByStageID(stageConfigID);
        for (JobRelationDto headRelation : headRelations) {
            List<JobRelationDto> chain =
                    jobRelationService.getJobRelationByStageIDAndHeadJobID(
                            stageConfigID,
                            headRelation.getJobID()
                    );
            for (JobRelationDto relation : chain) {
                JobBuild jobBuild = jobBuildByConfigID.get(relation.getJobID());
                if (jobBuild != null && jobBuild.getJobStatus() != BuildStatus.SUCCESS) {
                    runnableJobs.add(assembleJobBuildDto(jobBuild));
                    break;
                }
            }
        }
        return runnableJobs;
    }

    @Override
    public List<JobBuildDto> getTailJobBuildsByStageBuildID(Long stageConfigID, Long stageBuildID) {
        return getTailJobBuilds(
                stageConfigID,
                jobBuildDao.getJobBuildsByStageBuildID(stageBuildID)
        );
    }

    /**
     * Uses a locking read after the Stage row has been locked. Locking reads
     * bypass a stale REPEATABLE READ snapshot, so the last terminal Job
     * transaction observes every previously committed Job result.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<JobBuildDto> getTailJobBuildsForUpdate(
            Long stageConfigID,
            Long stageBuildID
    ) {
        return getTailJobBuilds(
                stageConfigID,
                jobBuildDao.getJobBuildsByStageBuildIDForUpdate(stageBuildID)
        );
    }

    private List<JobBuildDto> getTailJobBuilds(
            Long stageConfigID,
            List<JobBuild> jobBuilds
    ) {
        List<JobBuildDto> jobBuildDtos = new ArrayList<>();
        List<JobRelationDto> jobRelationDtos = jobRelationService.getAllTailJobRelationByStageID(stageConfigID);
        for (JobBuild build : jobBuilds) {
            for (JobRelationDto jobRelationDto : jobRelationDtos) {
                if (Objects.equals(jobRelationDto.getJobID(), build.getJobID())) {
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
        for (JobBuildDto jobBuildDto : jobBuildDtos) {
            switch (jobBuildDto.getStatus()) {
                case SUCCESS -> success++;
                case FAILURE -> failure++;
                case PENDING -> pending++;
                case RUNNING -> running++;
            }
        }
        int len = jobBuildDtos.size();
        if (failure >= 1) {
            return BuildStatus.FAILURE;
        }
        if (len == success) {
            return BuildStatus.SUCCESS;
        }
        if (len == pending) {
            return BuildStatus.PENDING;
        }
        return BuildStatus.RUNNING;
    }


    private JobBuild assembleJobBuild(JobBuildDto jobBuildDto, BuildStatus status) {
        JobBuild jobBuild = new JobBuild();
        jobBuild.setStageBuildID(jobBuildDto.getStageBuildID())
                .setJobID(jobBuildDto.getJobConfigID())
                .setExecutionAttempt(jobBuildDto.getExecutionAttempt())
                .setJobStatus(status);
        return jobBuild;
    }

    private JobBuildDto assembleJobBuildDto(JobBuild jobBuild) {
        JobBuildDto dto = new JobBuildDto();
        dto.setStageBuildID(jobBuild.getStageBuildID())
                .setJobConfigID(jobBuild.getJobID())
                .setJobBuildID(jobBuild.getId())
                .setExecutionAttempt(jobBuild.getExecutionAttempt())
                .setStatus(jobBuild.getJobStatus());
        return dto;
    }
}
