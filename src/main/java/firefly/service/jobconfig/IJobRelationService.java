package firefly.service.jobconfig;

import firefly.bean.dto.JobRelationDto;
import firefly.model.job.JobRelation;

import java.util.List;

public interface IJobRelationService {

    List<JobRelationDto> getJobRelationByStageIDAndHeadJobID(Long stageID, Long headJobID);

    JobRelationDto getNextJobRelation(Long stageID, Long jobID);

    List<JobRelationDto> getAllHeadJobRelationByStageID(Long stageID);

    List<JobRelationDto> getAllTailJobRelationByStageID(Long stageID);

    void saveJobRelation(JobRelationDto jobRelationDto);

    JobRelation assembleJobRelation(JobRelationDto jobRelationDto);

}
