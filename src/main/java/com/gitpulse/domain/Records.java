package com.gitpulse.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class Records {
    // API Responses
    public record RepoMetricsDto(String owner, String repo, List<StuckPrDto> stuckPrs, String fetchedAt) {}
    public record StuckPrDto(Long id, int number, String title, String author, String createdAt) {}

    // Stale Branch Response
    public record BranchHealthDto(String owner, String repo, int totalBranches, int staleBranches, List<BranchDto> branches, String fetchedAt) {}
    public record BranchDto(String name, String lastCommitDate, boolean isStale) {}

    // GitHub API Payloads
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubRepoSummary(String name, String description) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubPr(
        Long id, 
        int number, 
        String title, 
        @JsonProperty("created_at") String createdAt, 
        @JsonProperty("head") GitHubHead head,
        GitHubUser user
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubHead(String ref, GitHubRepository repo) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubRepository(Long id, @JsonProperty("default_branch") String defaultBranch) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubUser(Long id, String login) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubReview(String state, GitHubUser user) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubBranch(String name, GitHubCommitObject commit) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubCommitObject(@JsonProperty("commit") GitHubCommitDetailsContainer innerCommit) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubCommitDetailsContainer(GitHubCommitDetails committer) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubCommitDetails(String date) {}
}