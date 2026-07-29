package firefly.service.stagebuild;

import firefly.bean.dto.StageBuildDto;
import firefly.constant.BuildStatus;

import java.util.List;

public interface IStageBuildService {

    Long saveStageBuild(StageBuildDto stageBuildDto);

    StageBuildDto getStageBuildByID(Long id);

    StageBuildDto getFirstStageToRun(Long pipelineBuildID);

    List<StageBuildDto> getStageBuildsByPipelineBuildID(Long pipelineBuildID);

    Boolean updateStageBuildStatusByID(BuildStatus status, Long id);

    Boolean transitionStageBuildStatus(
            Long stageBuildID,
            BuildStatus expectedStatus,
            BuildStatus targetStatus);

    StageBuildDto getStageBuildByStageConfigIDAndPipelineBuildID(Long stageConfigID, Long pipelineBuildID);

}
