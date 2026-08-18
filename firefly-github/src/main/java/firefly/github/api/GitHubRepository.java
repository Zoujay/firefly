package firefly.github.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GitHubRepository(
    Long id,
    @JsonProperty("node_id") String nodeId,
    String name,
    @JsonProperty("full_name") String fullName,
    @JsonProperty("html_url") String htmlUrl,
    @JsonProperty("clone_url") String cloneUrl,
    @JsonProperty("default_branch") String defaultBranch,
    @JsonProperty("private") boolean privateRepository) {

}
