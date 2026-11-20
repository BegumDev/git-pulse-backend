package com.gitpulse.service;

import com.gitpulse.client.GitHubRestClient;
import com.gitpulse.client.GitHubUpstreamException;
import com.gitpulse.domain.Records.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.ClientWebApplicationException;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

@ApplicationScoped
public class MetricsService {

    private static final int GITHUB_PAGE_SIZE = 100;
    private static final Logger LOG = Logger.getLogger(MetricsService.class);

    @Inject
    @RestClient
    GitHubRestClient gitHubClient;

    @Inject
    MetricsCache cache;

    public List<GitHubRepoSummary> getOwnerRepositories(String owner) {
        return fetchAllPages(page -> gitHubClient.getUserRepos(owner, GITHUB_PAGE_SIZE, "updated", page));
    }

    public RepoMetricsDto calculateMetrics(String owner, String repo) {
        return cache.getOrFetch("repo-metrics", owner, repo, RepoMetricsDto.class,
            () -> fetchMetrics(owner, repo));
    }

    private RepoMetricsDto fetchMetrics(String owner, String repo) {
        List<GitHubPr> openPrs = fetchAllPages(
            page -> gitHubClient.getPullRequests(owner, repo, "open", GITHUB_PAGE_SIZE, page));
        List<StuckPrDto> stuckPrs = new ArrayList<>();
        
        Instant now = Instant.now();

        for (GitHubPr pr : openPrs) {
            Instant createdAt = Instant.parse(pr.createdAt());
            
            if (isOlderThan48Hours(createdAt, now)) {
                List<GitHubReview> reviews = fetchAllPages(
                    page -> gitHubClient.getPullRequestReviews(owner, repo, pr.number(), GITHUB_PAGE_SIZE, page));
                
                boolean hasApproval = hasCurrentApproval(reviews);

                if (!hasApproval) {
                    stuckPrs.add(new StuckPrDto(
                        pr.id(), 
                        pr.number(), 
                        pr.title(), 
                        pr.user().login(), 
                        pr.createdAt()
                    ));
                }
            }
        }

        return new RepoMetricsDto(owner, repo, stuckPrs, Instant.now().toString());
    }

    static boolean isOlderThan48Hours(Instant createdAt, Instant now) {
        return createdAt.isBefore(now.minus(48, ChronoUnit.HOURS));
    }

    private boolean hasCurrentApproval(List<GitHubReview> reviews) {
        Map<Long, Boolean> approvalsByReviewer = new HashMap<>();
        // GitHub returns reviews in chronological order, including across pages.
        for (GitHubReview review : reviews) {
            if (review.state() == null) {
                throw new IllegalStateException("GitHub review is missing its state");
            }
            switch (review.state()) {
                case "COMMENTED", "PENDING" -> { }
                case "APPROVED", "CHANGES_REQUESTED", "DISMISSED" -> {
                    if (review.user() == null || review.user().id() == null) {
                        throw new IllegalStateException("GitHub review is missing its reviewer ID");
                    }
                    approvalsByReviewer.put(review.user().id(), "APPROVED".equals(review.state()));
                }
                default -> throw new IllegalStateException("GitHub review has an unrecognized state");
            }
        }
        return approvalsByReviewer.containsValue(true);
    }

    public BranchHealthDto calculateBranchHygiene(String owner, String repo) {
        return cache.getOrFetch("branch-health", owner, repo, BranchHealthDto.class,
            () -> fetchBranchHygiene(owner, repo));
    }

    private BranchHealthDto fetchBranchHygiene(String owner, String repo) {
        GitHubRepository repository = callGitHub(() -> gitHubClient.getRepository(owner, repo));
        if (repository == null || repository.id() == null
            || repository.defaultBranch() == null || repository.defaultBranch().isBlank()) {
            throw new IllegalStateException("GitHub repository metadata is missing its ID or default branch");
        }
        List<GitHubBranch> branches = fetchAllPages(
            page -> gitHubClient.getBranches(owner, repo, GITHUB_PAGE_SIZE, page));
        List<GitHubPr> openPrs = fetchAllPages(
            page -> gitHubClient.getPullRequests(owner, repo, "open", GITHUB_PAGE_SIZE, page));

        for (GitHubPr pr : openPrs) {
            if (pr.head() == null || pr.head().ref() == null || pr.head().ref().isBlank()
                || (pr.head().repo() != null && pr.head().repo().id() == null)) {
                throw new IllegalStateException("GitHub pull request is missing its head branch or repository ID");
            }
        }
        Set<String> activePrBranches = openPrs.stream()
            .filter(pr -> pr.head().repo() != null && repository.id().equals(pr.head().repo().id()))
            .map(pr -> pr.head().ref())
            .collect(Collectors.toSet());

        Instant now = Instant.now();
        List<BranchDto> branchDtos = new ArrayList<>();
        int staleCount = 0;

        for (GitHubBranch branch : branches) {
            GitHubBranch detailedBranch = callGitHub(() -> gitHubClient.getBranch(owner, repo, branch.name()));
            String commitDateStr = detailedBranch.commit().innerCommit().committer().date();
            Instant commitDate = Instant.parse(commitDateStr);

            boolean isOld = isOlderThan30Days(commitDate, now);
            boolean hasOpenPr = activePrBranches.contains(branch.name());
            boolean isStale = isOld && !branch.name().equals(repository.defaultBranch()) && !hasOpenPr;

            if (isStale) {
                staleCount++;
            }

            branchDtos.add(new BranchDto(branch.name(), commitDateStr, isStale));
        }

        return new BranchHealthDto(owner, repo, branches.size(), staleCount, branchDtos, Instant.now().toString());
    }

    static boolean isOlderThan30Days(Instant commitDate, Instant now) {
        return commitDate.isBefore(now.minus(30, ChronoUnit.DAYS));
    }

    private <T> List<T> fetchAllPages(IntFunction<List<T>> fetchPage) {
        List<T> results = new ArrayList<>();
        List<T> pageResults;
        int page = 1;
        do {
            int currentPage = page++;
            pageResults = callGitHub(() -> fetchPage.apply(currentPage));
            results.addAll(pageResults);
        } while (pageResults.size() == GITHUB_PAGE_SIZE);
        return results;
    }

    private <T> T callGitHub(Supplier<T> request) {
        try {
            return request.get();
        } catch (ClientWebApplicationException e) {
            LOG.warn("GitHub API response could not be processed");
            throw new GitHubUpstreamException(502, "GitHub response invalid",
                "GitHub returned an invalid response. Please try again later.");
        } catch (ProcessingException e) {
            boolean timedOut = false;
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof SocketTimeoutException || cause instanceof TimeoutException) {
                    timedOut = true;
                    break;
                }
            }
            if (timedOut) {
                LOG.warn("GitHub API request timed out");
                throw new GitHubUpstreamException(504, "GitHub request timed out",
                    "GitHub did not respond in time. Please try again later.");
            }
            LOG.warn("GitHub API could not be reached or its response could not be processed");
            throw new GitHubUpstreamException(503, "GitHub unavailable",
                "GitHub could not be reached or returned an invalid response. Please try again later.");
        }
    }


    public void invalidateMetricsCache(String owner, String repo) {
        cache.evict("repo-metrics", owner, repo);
    }

    public void invalidateBranchCache(String owner, String repo) {
        cache.evict("branch-health", owner, repo);
    }
}
