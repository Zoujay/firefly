package firefly.service.pipelinebuild;

public record PipelineRetryPreparedEvent(Long stageBuildID, Integer executionAttempt) {}
