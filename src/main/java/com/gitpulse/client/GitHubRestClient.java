package com.gitpulse.client;

import com.gitpulse.domain.Records.GitHubBranch;
import com.gitpulse.domain.Records.GitHubPr;
import com.gitpulse.domain.Records.GitHubRepository;
import com.gitpulse.domain.Records.GitHubRepoSummary;
import com.gitpulse.domain.Records.GitHubReview;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import io.quarkus.rest.client.reactive.ClientExceptionMapper;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

@RegisterRestClient(configKey = "github-api")
@ClientHeaderParam(name = "Authorization", value = "Bearer ${github.token}")
@ClientHeaderParam(name = "Accept", value = "application/vnd.github.v3+json")
@ClientHeaderParam(name = "User-Agent", value = "GitPulse-API")
public interface GitHubRestClient {

    @ClientExceptionMapper
    static GitHubUpstreamException mapGitHubError(Response response) {
        int status = response.getStatus();
        Logger.getLogger(GitHubRestClient.class).warnf("GitHub API returned HTTP %d", status);
        if (status == 404) {
            return new GitHubUpstreamException(404, "Not found", "GitHub owner or repository not found.");
        }
        if (status == 429 || (status == 403
            && ("0".equals(response.getHeaderString("x-ratelimit-remaining"))
                || response.getHeaderString("retry-after") != null))) {
            return new GitHubUpstreamException(503, "GitHub rate limit reached",
                "GitHub's API rate limit was reached. Please try again later.");
        }
        if (status == 403) {
            return new GitHubUpstreamException(503, "GitHub request denied",
                "GitHub denied the request or applied a rate limit. Please try again later.");
        }
        if (status >= 500) {
            return new GitHubUpstreamException(503, "GitHub unavailable",
                "GitHub is temporarily unavailable. Please try again later.");
        }
        return new GitHubUpstreamException(502, "GitHub request failed",
            "GitHub could not process the request.");
    }

    @GET
    @Path("/repos/{owner}/{repo}")
    GitHubRepository getRepository(
        @PathParam("owner") String owner,
        @PathParam("repo") String repo
    );

    @GET
    @Path("/users/{owner}/repos")
    List<GitHubRepoSummary> getUserRepos(
        @PathParam("owner") String owner,
        @QueryParam("per_page") int perPage,
        @QueryParam("sort") String sort,
        @QueryParam("page") int page
    );

    @GET
    @Path("/repos/{owner}/{repo}/pulls")
    List<GitHubPr> getPullRequests(
        @PathParam("owner") String owner, 
        @PathParam("repo") String repo, 
        @QueryParam("state") String state,
        @QueryParam("per_page") int perPage,
        @QueryParam("page") int page
    );

    @GET
    @Path("/repos/{owner}/{repo}/pulls/{pull_number}/reviews")
    List<GitHubReview> getPullRequestReviews(
        @PathParam("owner") String owner, 
        @PathParam("repo") String repo, 
        @PathParam("pull_number") int pullNumber,
        @QueryParam("per_page") int perPage,
        @QueryParam("page") int page
    );

    @GET
    @Path("/repos/{owner}/{repo}/branches")
    List<GitHubBranch> getBranches(
        @PathParam("owner") String owner, 
        @PathParam("repo") String repo,
        @QueryParam("per_page") int perPage,
        @QueryParam("page") int page
    );

    @GET
    @Path("/repos/{owner}/{repo}/branches/{branch}")
    GitHubBranch getBranch(
        @PathParam("owner") String owner, 
        @PathParam("repo") String repo,
        @PathParam("branch") String branch
    );
}