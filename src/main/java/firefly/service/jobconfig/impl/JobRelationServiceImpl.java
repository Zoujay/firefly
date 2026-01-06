package firefly.service.jobconfig.impl;


import firefly.bean.dto.JobRelationDto;
import firefly.dao.jobconfig.IJobRelationDao;
import firefly.model.job.JobRelation;
import firefly.service.jobconfig.IJobRelationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class JobRelationServiceImpl implements IJobRelationService {

    @Autowired
    private IJobRelationDao jobRelationDao;

    @Override
    public List<JobRelationDto> getJobRelationByStageIDAndHeadJobID(Long stageID, Long headJobID) {
        List<JobRelation> jobRelations = jobRelationDao.getJobRelationsByStageID(stageID);
        List<JobRelationDto> result = new ArrayList<>();
        JobRelationDto head = null;
        Map<Long, JobRelation> map = new HashMap<>();
        for (JobRelation jobRelation : jobRelations) {
            map.put(jobRelation.getJobID(), jobRelation);
            if (jobRelation.getJobID().equals(headJobID)) {
                head = assembleJobRelationDto(jobRelation);
            }
        }
        if (head == null) {
            return result;
        }
        result.add(head);
        long nextJob = head.getNextJobID();
        while (true) {
            JobRelation jobRelation = map.getOrDefault(nextJob, null);
            if (jobRelation == null) {
                break;
            }
            JobRelationDto dto = assembleJobRelationDto(jobRelation);
            result.add(dto);
            nextJob = jobRelation.getNextJobID();
        }
        return result;
    }

    @Override
    public JobRelationDto getNextJobRelation(Long stageID, Long jobID) {
        List<JobRelation> jobRelations = jobRelationDao.getJobRelationsByStageID(stageID);
        Long nextJobRelationID = -1L;
        JobRelation nextJobRelation = null;
        for(JobRelation jobRelation : jobRelations) {
            if(jobRelation.getJobID().equals(jobID)) {
                nextJobRelationID = jobRelation.getId();
                break;
            }
        }
        for(JobRelation jobRelation : jobRelations) {
            if(jobRelation.getId().equals(nextJobRelationID)) {
                nextJobRelation =  jobRelation;
            }
        }
        return nextJobRelation == null ? null : assembleJobRelationDto(nextJobRelation);
    }

    @Override
    public List<JobRelationDto> getAllHeadJobRelationByStageID(Long stageID) {
        List<JobRelation> jobRelations = jobRelationDao.getAllHeadJobRelationsByStageID(stageID, true);
        List<JobRelationDto> result = new ArrayList<>();
        for (JobRelation jobRelation : jobRelations) {
            JobRelationDto dto = assembleJobRelationDto(jobRelation);
            result.add(dto);
        }
        return result;
    }

    @Override
    public List<JobRelationDto> getAllJobRelationByStageID(Long stageID) {
        List<JobRelation> jobRelations = jobRelationDao.getJobRelationsByStageID(stageID);
        List<JobRelationDto> result = new ArrayList<>();
        for (JobRelation jobRelation : jobRelations) {
            result.add(assembleJobRelationDto(jobRelation));
        }
        return result;
    }

    @Override
    public List<JobRelationDto> getAllTailJobRelationByStageID(Long stageID) {
        List<JobRelation> jobRelations = jobRelationDao.getAllTailJobRelationsByStageID(stageID);
        List<JobRelationDto> result = new ArrayList<>();
        for (JobRelation jobRelation : jobRelations) {
            JobRelationDto dto = assembleJobRelationDto(jobRelation);
            result.add(dto);
        }
        return result;
    }

    @Override
    public void saveJobRelation(JobRelationDto jobRelationDto) {
        JobRelation jobRelation = this.assembleJobRelation(jobRelationDto);
        jobRelationDao.save(jobRelation);
    }


    private JobRelationDto assembleJobRelationDto(JobRelation jobRelation) {
        JobRelationDto jobRelationDto = new JobRelationDto();
        jobRelationDto.setJobID(jobRelation.getJobID())
                .setPreviousJobID(jobRelation.getPreviousJobID())
                .setNextJobID(jobRelation.getNextJobID())
                .setPipelineID(jobRelation.getPipelineID())
                .setStageID(jobRelation.getStageID())
                .setIsHeadJob(jobRelation.isHeadJob())
                .setId(jobRelation.getId());
        return jobRelationDto;
    }

    public JobRelation assembleJobRelation(JobRelationDto jobRelationDto) {
        JobRelation jobRelation = new JobRelation();
        jobRelation.setJobID(jobRelationDto.getJobID())
                .setPreviousJobID(jobRelationDto.getPreviousJobID())
                .setNextJobID(jobRelationDto.getNextJobID())
                .setPipelineID(jobRelationDto.getPipelineID())
                .setStageID(jobRelationDto.getStageID())
                .setHeadJob(jobRelationDto.getIsHeadJob());
        return jobRelation;
    }

}
