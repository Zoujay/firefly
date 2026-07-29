package firefly.service.stagebuild.impl;

import firefly.bean.dto.StageBuildDto;
import firefly.constant.BuildStatus;
import firefly.dao.stagebuild.IStageBuildDao;
import firefly.model.stage.StageBuild;
import firefly.service.stagebuild.IStageBuildService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class StageBuildServiceImpl implements IStageBuildService {

    @Autowired
    private IStageBuildDao stageBuildDao;

    @Override
    public Long saveStageBuild(StageBuildDto stageBuildDto) {
        StageBuild stageBuild = this.assembleStageBuild(stageBuildDto);
        stageBuildDao.save(stageBuild);
        if (stageBuild.getId() != null && stageBuild.getId() > 0) {
            return stageBuild.getId();
        }
        return -1L;
    }

    @Override
    public StageBuildDto getStageBuildByID(Long id) {
        Optional<StageBuild> stageBuild = stageBuildDao.findById(id);
        return stageBuild.map(this::assembleStageBuildDto).orElse(null);
    }

    @Override
    public StageBuildDto getFirstStageToRun(Long pipelineBuildID) {
        List<StageBuild> stageBuilds = stageBuildDao.getStageBuildByPipelineBuildID(pipelineBuildID);
        if (CollectionUtils.isEmpty(stageBuilds)) {
            return null;
        }
        StageBuildDto stageBuildDto = new StageBuildDto();
        stageBuildDto.setPipelineBuildID(pipelineBuildID);
        for (StageBuild stageBuild : stageBuilds) {
            if (stageBuild.getStageStatus().name().equals(BuildStatus.PENDING.name())) {
                stageBuildDto.setStageBuildID(stageBuild.getId());
                stageBuildDto.setPipelineBuildID(stageBuild.getPipelineBuildID());
                stageBuildDto.setExecutionAttempt(stageBuild.getExecutionAttempt());
                stageBuildDto.setStatus(stageBuild.getStageStatus());
                return stageBuildDto;
            }
        }
        return stageBuildDto;
    }

    @Override
    public List<StageBuildDto> getStageBuildsByPipelineBuildID(Long pipelineBuildID) {
        List<StageBuild> stageBuilds = stageBuildDao.getStageBuildByPipelineBuildID(pipelineBuildID);
        if (CollectionUtils.isEmpty(stageBuilds)) {
            return Collections.emptyList();
        }
        List<StageBuildDto> result = new ArrayList<>();
        stageBuilds.forEach(stageBuild -> result.add(this.assembleStageBuildDto(stageBuild)));
        return result;
    }

    @Override
    public Boolean updateStageBuildStatusByID(
            BuildStatus status,
            Long id,
            Integer executionAttempt
    ) {
        Integer res = stageBuildDao.updateStageBuildStatusByID(
                status,
                id,
                executionAttempt
        );
        return res >= 1;
    }

    @Override
    public Boolean transitionStageBuildStatus(
            Long stageBuildID,
            BuildStatus expectedStatus,
            BuildStatus targetStatus,
            Integer executionAttempt) {
        Integer affectedRows = stageBuildDao.transitionStageBuildStatus(
                stageBuildID,
                expectedStatus,
                targetStatus,
                executionAttempt);
        return affectedRows == 1;
    }

    @Override
    public StageBuildDto getStageBuildByStageConfigIDAndPipelineBuildID(
            Long stageConfigID, Long pipelineBuildID) {
        Optional<StageBuild> stageBuild =
                stageBuildDao.getStageBuildByStageConfigIDAndPipelineBuildID(stageConfigID, pipelineBuildID);
        return stageBuild.map(this::assembleStageBuildDto).orElse(null);
    }


    private StageBuild assembleStageBuild(StageBuildDto stageBuildDto) {
        StageBuild stageBuild = new StageBuild();
        stageBuild.setStageStatus(stageBuildDto.getStatus())
                .setPipelineBuildID(stageBuildDto.getPipelineBuildID())
                .setExecutionAttempt(stageBuildDto.getExecutionAttempt())
                .setStageID(stageBuildDto.getStageConfigID());
        return stageBuild;
    }

    private StageBuildDto assembleStageBuildDto(StageBuild stageBuild) {
        StageBuildDto stageBuildDto = new StageBuildDto();
        stageBuildDto.setStageBuildID(stageBuild.getId())
                .setStatus(stageBuild.getStageStatus())
                .setPipelineBuildID(stageBuild.getPipelineBuildID())
                .setExecutionAttempt(stageBuild.getExecutionAttempt())
                .setStageConfigID(stageBuild.getStageID());
        return stageBuildDto;
    }
}
