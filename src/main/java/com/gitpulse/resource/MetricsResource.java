package com.gitpulse.resource;

import com.gitpulse.domain.Records.BranchHealthDto;
import com.gitpulse.domain.Records.GitHubRepoSummary;
import com.gitpulse.domain.Records.RepoMetricsDto;
import com.gitpulse.service.MetricsService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/v1/metrics")
@Produces(MediaType.APPLICATION_JSON)
public class MetricsResource {

    @Inject
    MetricsService metricsService;

    @GET
    @Path("/{owner}")
    public Response getOwnerRepos(@PathParam("owner") String owner) {
        List<GitHubRepoSummary> repos = metricsService.getOwnerRepositories(owner);
        return Response.ok(repos).build();
    }

    @GET
    @Path("/{owner}/{repo}")
    public Response getRepoMetrics(@PathParam("owner") String owner, @PathParam("repo") String repo) {
        RepoMetricsDto metrics = metricsService.calculateMetrics(owner, repo);
        return Response.ok(metrics).build();
    }

    @GET
    @Path("/{owner}/{repo}/stale-branches")
    public Response getStaleBranches(@PathParam("owner") String owner, @PathParam("repo") String repo) {
        BranchHealthDto branchHealth = metricsService.calculateBranchHygiene(owner, repo);
        return Response.ok(branchHealth).build();
    }
}