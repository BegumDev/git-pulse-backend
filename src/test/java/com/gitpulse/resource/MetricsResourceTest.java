package com.gitpulse.resource;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@QuarkusTestResource(GitHubWiremockResource.class)
@DisplayName("GitPulse Unit & Component Acceptance Criteria Tests")
class MetricsResourceTest {

    private WireMockServer wireMock;

    @BeforeEach
    void setup() {
        wireMock = GitHubWiremockResource.getServer();
        wireMock.resetAll();
    }

    @Test
    @DisplayName("AC 1.1 & AC 1.2: Flags open PRs > 48h without approval and excludes PRs < 48h old or already approved")
    void testStuckPrIdentificationAndFiltering() {
        String olderThan48h = Instant.now().minus(72, ChronoUnit.HOURS).toString();
        String newerThan48h = Instant.now().minus(6, ChronoUnit.HOURS).toString();

        wireMock.stubFor(get(urlPathEqualTo("/repos/testowner/pr-repo/pulls"))
            .willReturn(okJson("""
                [
                  {
                    "id": 1001,
                    "number": 1,
                    "title": "Stuck PR",
                    "created_at": "%s",
                    "head": { "ref": "feat/stuck" },
                    "user": { "login": "dev1" }
                  },
                  {
                    "id": 1002,
                    "number": 2,
                    "title": "Approved Old PR",
                    "created_at": "%s",
                    "head": { "ref": "feat/approved" },
                    "user": { "login": "dev2" }
                  },
                  {
                    "id": 1003,
                    "number": 3,
                    "title": "Fresh Recent PR",
                    "created_at": "%s",
                    "head": { "ref": "feat/fresh" },
                    "user": { "login": "dev3" }
                  }
                ]
                """.formatted(olderThan48h, olderThan48h, newerThan48h))));

        wireMock.stubFor(get(urlPathEqualTo("/repos/testowner/pr-repo/pulls/1/reviews"))
            .willReturn(okJson("""
                [ { "state": "COMMENTED" } ]
                """)));

        wireMock.stubFor(get(urlPathEqualTo("/repos/testowner/pr-repo/pulls/2/reviews"))
            .willReturn(okJson("""
                [ { "state": "APPROVED", "user": { "id": 11, "login": "reviewer" } } ]
                """)));

        given()
            .when().get("/api/v1/metrics/testowner/pr-repo")
            .then()
            .statusCode(200)
            .body("owner", is("testowner"))
            .body("repo", is("pr-repo"))
            .body("stuckPrs", hasSize(1))
            .body("stuckPrs[0].id", is(1001))
            .body("stuckPrs[0].number", is(1))
            .body("stuckPrs[0].title", is("Stuck PR"))
            .body("stuckPrs[0].author", is("dev1"))
            .body("stuckPrs[0].createdAt", is(olderThan48h));

    }

    @Test
    @DisplayName("AC 1.3: Returns HTTP 404 with structured error JSON for a non-existent repository")
    void testNonExistentRepositoryReturns404() {
        wireMock.stubFor(get(urlPathEqualTo("/repos/testowner/unknown-repo/pulls"))
            .willReturn(aResponse().withStatus(404)));

        given()
            .when().get("/api/v1/metrics/testowner/unknown-repo")
            .then()
            .statusCode(404)
            .body("status", is(404))
            .contentType("application/problem+json")
            .body("detail", is("GitHub owner or repository not found."));

    }

    @Test
    @DisplayName("AC 2.1, AC 2.2 & AC 2.3: Flags stale branches > 30d, excludes branches with open PRs, and returns branch counts")
    void testStaleBranchHygieneAndCounts() {
        String staleCommitDate = Instant.now().minus(45, ChronoUnit.DAYS).toString();
        String freshCommitDate = Instant.now().minus(3, ChronoUnit.DAYS).toString();

        wireMock.stubFor(get(urlPathEqualTo("/repos/testowner/branch-repo"))
            .willReturn(okJson("{\"id\": 123, \"default_branch\": \"main\"}")));
        wireMock.stubFor(get(urlPathEqualTo("/repos/testowner/branch-repo/branches"))
            .willReturn(okJson("""
                [
                  { "name": "main" },
                  { "name": "feature/abandoned" },
                  { "name": "feature/active-pr" },
                  { "name": "feature/fork" },
                  { "name": "feature/deleted" },
                  { "name": "feature/recent" }
                ]
                """)));

        wireMock.stubFor(get(urlPathEqualTo("/repos/testowner/branch-repo/pulls"))
            .willReturn(okJson("""
                [
                  {
                    "id": 2001,
                    "number": 42,
                    "title": "Long running PR",
                    "created_at": "%s",
                    "head": { "ref": "feature/active-pr", "repo": { "id": 123 } },
                    "user": { "login": "dev1" }
                  },
                  {
                    "head": { "ref": "feature/fork", "repo": { "id": 456 } }
                  },
                  {
                    "head": { "ref": "feature/deleted", "repo": null }
                  }
                ]
                """.formatted(staleCommitDate))));

        wireMock.stubFor(get(urlEqualTo("/repos/testowner/branch-repo/branches/main"))
            .willReturn(okJson("""
                { "name": "main", "commit": { "commit": { "committer": { "date": "%s" } } } }
                """.formatted(staleCommitDate))));

        wireMock.stubFor(get(urlEqualTo("/repos/testowner/branch-repo/branches/feature%2Fabandoned"))
            .willReturn(okJson("""
                { "name": "feature/abandoned", "commit": { "commit": { "committer": { "date": "%s" } } } }
                """.formatted(staleCommitDate))));

        wireMock.stubFor(get(urlEqualTo("/repos/testowner/branch-repo/branches/feature%2Factive-pr"))
            .willReturn(okJson("""
                { "name": "feature/active-pr", "commit": { "commit": { "committer": { "date": "%s" } } } }
                """.formatted(staleCommitDate))));

        wireMock.stubFor(get(urlEqualTo("/repos/testowner/branch-repo/branches/feature%2Ffork"))
            .willReturn(okJson("""
                { "commit": { "commit": { "committer": { "date": "%s" } } } }
                """.formatted(staleCommitDate))));
        wireMock.stubFor(get(urlEqualTo("/repos/testowner/branch-repo/branches/feature%2Fdeleted"))
            .willReturn(okJson("""
                { "commit": { "commit": { "committer": { "date": "%s" } } } }
                """.formatted(staleCommitDate))));
        wireMock.stubFor(get(urlEqualTo("/repos/testowner/branch-repo/branches/feature%2Frecent"))
            .willReturn(okJson("""
                { "commit": { "commit": { "committer": { "date": "%s" } } } }
                """.formatted(freshCommitDate))));

        given()
            .when().get("/api/v1/metrics/testowner/branch-repo/stale-branches")
            .then()
            .statusCode(200)
            .body("owner", is("testowner"))
            .body("repo", is("branch-repo"))
            .body("totalBranches", is(6))
            .body("staleBranches", is(3))
            .body("branches.find { it.name == 'main' }.isStale", is(false))
            .body("branches.find { it.name == 'feature/abandoned' }.isStale", is(true))
            .body("branches.find { it.name == 'feature/active-pr' }.isStale", is(false))
            .body("branches.find { it.name == 'feature/fork' }.isStale", is(true))
            .body("branches.find { it.name == 'feature/deleted' }.isStale", is(true))
            .body("branches.find { it.name == 'feature/recent' }.isStale", is(false));
    }

    @Test
    void failsBranchAnalysisWhenRepositoryMetadataIsIncomplete() {
        wireMock.stubFor(get(urlPathEqualTo("/repos/testowner/missing-metadata"))
            .willReturn(okJson("{\"id\": 123}")));

        given()
            .when().get("/api/v1/metrics/testowner/missing-metadata/stale-branches")
            .then()
            .statusCode(500);
    }

    @Test
    void failsBranchAnalysisWhenPullRequestRepositoryIdIsMissing() {
        wireMock.stubFor(get(urlPathEqualTo("/repos/testowner/missing-pr-id"))
            .willReturn(okJson("{\"id\": 123, \"default_branch\": \"main\"}")));
        wireMock.stubFor(get(urlPathEqualTo("/repos/testowner/missing-pr-id/branches"))
            .willReturn(okJson("[]")));
        wireMock.stubFor(get(urlPathEqualTo("/repos/testowner/missing-pr-id/pulls"))
            .willReturn(okJson("[{\"head\":{\"ref\":\"feature\", \"repo\":{}}}]")));

        given()
            .when().get("/api/v1/metrics/testowner/missing-pr-id/stale-branches")
            .then()
            .statusCode(500);
    }

    @Test
    @DisplayName("AC 3.1: Returns list of public repositories when searching by owner alone")
    void testOwnerRepositoryDiscovery() {
        wireMock.stubFor(get(urlPathEqualTo("/users/testowner/repos"))
            .willReturn(okJson("""
                [
                  { "name": "git-pulse", "description": "Repository health tracker" },
                  { "name": "infra-scripts", "description": null }
                ]
                """)));

        given()
            .when().get("/api/v1/metrics/testowner")
            .then()
            .statusCode(200)
            .body("$", hasSize(2))
            .body("[0].name", is("git-pulse"))
            .body("[0].description", is("Repository health tracker"))
            .body("[1].name", is("infra-scripts"));
    }

    @Test
    void returnsRepositoriesFromLaterPages() {
        String firstPage = IntStream.range(0, 100)
            .mapToObj(i -> "{\"name\":\"repo-" + i + "\"}")
            .collect(Collectors.joining(",", "[", "]"));
        stubPage("/users/paged-owner/repos", 1, firstPage);
        stubPage("/users/paged-owner/repos", 2, "[{\"name\":\"last-repo\"}]");

        given()
            .when().get("/api/v1/metrics/paged-owner")
            .then()
            .statusCode(200)
            .body("$", hasSize(101))
            .body("[100].name", is("last-repo"));

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/users/paged-owner/repos"))
            .withQueryParam("page", equalTo("2"))
            .withQueryParam("per_page", equalTo("100")));
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/users/paged-owner/repos"))
            .withQueryParam("page", equalTo("3")));
    }

    @Test
    void stopsAfterAnEmptyPageWhenResultCountIsExactlyOnePage() {
        String firstPage = IntStream.range(0, 100)
            .mapToObj(i -> "{\"name\":\"repo-" + i + "\"}")
            .collect(Collectors.joining(",", "[", "]"));
        stubPage("/users/exact-page/repos", 1, firstPage);
        stubPage("/users/exact-page/repos", 2, "[]");

        given()
            .when().get("/api/v1/metrics/exact-page")
            .then()
            .statusCode(200)
            .body("$", hasSize(100));

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/users/exact-page/repos"))
            .withQueryParam("page", equalTo("2")));
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/users/exact-page/repos"))
            .withQueryParam("page", equalTo("3")));
    }

    @Test
    void usesLaterPagesOfPullRequestsAndReviews() {
        String fresh = Instant.now().minus(1, ChronoUnit.HOURS).toString();
        String old = Instant.now().minus(72, ChronoUnit.HOURS).toString();
        String firstPage = IntStream.range(1, 101)
            .mapToObj(i -> "{\"id\":" + i + ",\"number\":" + i
                + ",\"created_at\":\"" + fresh + "\"}")
            .collect(Collectors.joining(",", "[", "]"));
        stubPage("/repos/paged-owner/pr-pages/pulls", 1, firstPage);
        stubPage("/repos/paged-owner/pr-pages/pulls", 2, """
            [
              {"id": 101, "number": 101, "title": "Approved", "created_at": "%s", "user": {"login": "dev"}},
              {"id": 102, "number": 102, "title": "Stuck", "created_at": "%s", "user": {"login": "dev"}}
            ]
            """.formatted(old, old));
        String reviews = IntStream.range(0, 100)
            .mapToObj(i -> "{\"state\":\"COMMENTED\"}")
            .collect(Collectors.joining(",", "[", "]"));
        stubPage("/repos/paged-owner/pr-pages/pulls/101/reviews", 1, reviews);
        stubPage("/repos/paged-owner/pr-pages/pulls/101/reviews", 2,
            "[{\"state\":\"APPROVED\", \"user\":{\"id\":11,\"login\":\"reviewer\"}}]");
        stubPage("/repos/paged-owner/pr-pages/pulls/102/reviews", 1, "[]");

        given()
            .when().get("/api/v1/metrics/paged-owner/pr-pages")
            .then()
            .statusCode(200)
            .body("stuckPrs", hasSize(1))
            .body("stuckPrs[0].number", is(102));
    }

    @Test
    void usesLatestDecisionForEachReviewerWithoutLettingCommentsEraseApproval() {
        String old = Instant.now().minus(72, ChronoUnit.HOURS).toString();
        String prs = IntStream.rangeClosed(1, 6)
            .mapToObj(i -> "{\"id\":" + i + ",\"number\":" + i
                + ",\"title\":\"PR " + i + "\",\"created_at\":\"" + old
                + "\",\"user\":{\"login\":\"author\"}}")
            .collect(Collectors.joining(",", "[", "]"));
        stubPage("/repos/testowner/review-decisions/pulls", 1, prs);
        stubPage("/repos/testowner/review-decisions/pulls/1/reviews", 1, """
            [{"state":"APPROVED","user":{"id":1}},
             {"state":"CHANGES_REQUESTED","user":{"id":1}}]
            """);
        stubPage("/repos/testowner/review-decisions/pulls/2/reviews", 1, """
            [{"state":"DISMISSED","user":{"id":1}}]
            """);
        stubPage("/repos/testowner/review-decisions/pulls/3/reviews", 1, """
            [{"state":"APPROVED","user":{"id":1}},
             {"state":"COMMENTED","user":{"id":1}}]
            """);
        stubPage("/repos/testowner/review-decisions/pulls/4/reviews", 1, """
            [{"state":"CHANGES_REQUESTED","user":{"id":1}},
             {"state":"APPROVED","user":{"id":1}}]
            """);
        stubPage("/repos/testowner/review-decisions/pulls/5/reviews", 1, """
            [{"state":"APPROVED","user":{"id":1}},
             {"state":"CHANGES_REQUESTED","user":{"id":2}}]
            """);
        stubPage("/repos/testowner/review-decisions/pulls/6/reviews", 1, """
            [{"state":"APPROVED","user":{"id":1}},
             {"state":"DISMISSED","user":{"id":1}}]
            """);

        given()
            .when().get("/api/v1/metrics/testowner/review-decisions")
            .then()
            .statusCode(200)
            .body("stuckPrs.number", containsInAnyOrder(1, 2, 6));
    }

    @Test
    void laterReviewPageCanSupersedeEarlierApproval() {
        String old = Instant.now().minus(72, ChronoUnit.HOURS).toString();
        stubPage("/repos/testowner/review-pages/pulls", 1, """
            [{"id":1,"number":1,"title":"PR","created_at":"%s","user":{"login":"author"}}]
            """.formatted(old));
        String firstPage = IntStream.range(0, 100)
            .mapToObj(i -> i == 0
                ? "{\"state\":\"APPROVED\",\"user\":{\"id\":1}}"
                : "{\"state\":\"COMMENTED\"}")
            .collect(Collectors.joining(",", "[", "]"));
        stubPage("/repos/testowner/review-pages/pulls/1/reviews", 1, firstPage);
        stubPage("/repos/testowner/review-pages/pulls/1/reviews", 2,
            "[{\"state\":\"CHANGES_REQUESTED\",\"user\":{\"id\":1}}]");

        given()
            .when().get("/api/v1/metrics/testowner/review-pages")
            .then()
            .statusCode(200)
            .body("stuckPrs.number", contains(1));
    }

    @Test
    void failsInsteadOfGuessingWhenDecisionHasNoReviewer() {
        String old = Instant.now().minus(72, ChronoUnit.HOURS).toString();
        stubPage("/repos/testowner/missing-reviewer/pulls", 1, """
            [{"id":1,"number":1,"title":"PR","created_at":"%s","user":{"login":"author"}}]
            """.formatted(old));
        stubPage("/repos/testowner/missing-reviewer/pulls/1/reviews", 1,
            "[{\"state\":\"APPROVED\",\"user\":null}]");

        given()
            .when().get("/api/v1/metrics/testowner/missing-reviewer")
            .then()
            .statusCode(500);
    }

    @Test
    void countsBranchesFromLaterPages() {
        String firstPage = IntStream.range(0, 100)
            .mapToObj(i -> "{\"name\":\"branch-" + i + "\"}")
            .collect(Collectors.joining(",", "[", "]"));
        wireMock.stubFor(get(urlPathEqualTo("/repos/paged-owner/branch-pages"))
            .willReturn(okJson("{\"id\": 123, \"default_branch\": \"main\"}")));
        stubPage("/repos/paged-owner/branch-pages/branches", 1, firstPage);
        stubPage("/repos/paged-owner/branch-pages/branches", 2, "[{\"name\":\"last-branch\"}]");
        stubPage("/repos/paged-owner/branch-pages/pulls", 1, "[]");
        wireMock.stubFor(get(urlPathMatching("/repos/paged-owner/branch-pages/branches/.*"))
            .willReturn(okJson("""
                {"commit":{"commit":{"committer":{"date":"2020-01-01T00:00:00Z"}}}}
                """)));

        given()
            .when().get("/api/v1/metrics/paged-owner/branch-pages/stale-branches")
            .then()
            .statusCode(200)
            .body("totalBranches", is(101))
            .body("staleBranches", is(101))
            .body("branches.find { it.name == 'last-branch' }.isStale", is(true));
    }

    @Test
    void failsInsteadOfReturningPartialResultsWhenLaterPageFails() {
        String firstPage = IntStream.range(0, 100)
            .mapToObj(i -> "{\"name\":\"repo-" + i + "\"}")
            .collect(Collectors.joining(",", "[", "]"));
        stubPage("/users/failing-owner/repos", 1, firstPage);
        wireMock.stubFor(get(urlPathEqualTo("/users/failing-owner/repos"))
            .withQueryParam("page", equalTo("2"))
            .withQueryParam("per_page", equalTo("100"))
            .willReturn(aResponse().withStatus(503)));

        given()
            .when().get("/api/v1/metrics/failing-owner")
            .then()
            .statusCode(503)
            .body("title", is("GitHub unavailable"));
    }

    @Test
    void reportsGitHubRateLimitWithoutExposingUpstreamDetails() {
        wireMock.stubFor(get(urlPathEqualTo("/users/rate-limited/repos"))
            .willReturn(aResponse().withStatus(403)
                .withHeader("x-ratelimit-remaining", "0")
                .withBody("sensitive upstream response")));

        given()
            .when().get("/api/v1/metrics/rate-limited")
            .then()
            .statusCode(503)
            .contentType("application/problem+json")
            .body("type", is("about:blank"))
            .body("title", is("GitHub rate limit reached"))
            .body("status", is(503))
            .body("detail", not(containsString("sensitive upstream response")));
    }

    @Test
    void reportsSecondaryRateLimitAndAmbiguousForbiddenResponses() {
        wireMock.stubFor(get(urlPathEqualTo("/users/secondary-limit/repos"))
            .willReturn(aResponse().withStatus(429)));
        wireMock.stubFor(get(urlPathEqualTo("/users/retry-limit/repos"))
            .willReturn(aResponse().withStatus(403).withHeader("retry-after", "60")));
        wireMock.stubFor(get(urlPathEqualTo("/users/forbidden-owner/repos"))
            .willReturn(aResponse().withStatus(403).withBody("private upstream detail")));

        given().when().get("/api/v1/metrics/secondary-limit")
            .then().statusCode(503).body("title", is("GitHub rate limit reached"));
        given().when().get("/api/v1/metrics/retry-limit")
            .then().statusCode(503).body("title", is("GitHub rate limit reached"));
        given().when().get("/api/v1/metrics/forbidden-owner")
            .then().statusCode(503)
            .body("detail", containsString("denied"))
            .body("detail", not(containsString("private upstream detail")));
    }

    @Test
    void reportsUpstreamAuthenticationFailureWithoutLeakingResponse() {
        wireMock.stubFor(get(urlPathEqualTo("/users/auth-failure/repos"))
            .willReturn(aResponse().withStatus(401).withBody("private upstream detail")));

        given().when().get("/api/v1/metrics/auth-failure")
            .then().statusCode(502).contentType("application/problem+json")
            .body("title", is("GitHub request failed"))
            .body("detail", not(containsString("private upstream detail")));
    }

    @Test
    void reportsTimedOutGitHubRequest() {
        wireMock.stubFor(get(urlPathEqualTo("/users/slow-owner/repos"))
            .willReturn(okJson("[]").withFixedDelay(1500)));

        given().when().get("/api/v1/metrics/slow-owner")
            .then().statusCode(504).contentType("application/problem+json")
            .body("title", is("GitHub request timed out"));
    }

    @Test
    void reportsConnectionFailureWithoutExposingTechnicalDetails() {
        wireMock.stubFor(get(urlPathEqualTo("/users/disconnected/repos"))
            .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.EMPTY_RESPONSE)));

        given().when().get("/api/v1/metrics/disconnected")
            .then().statusCode(503).contentType("application/problem+json")
            .body("title", is("GitHub unavailable"));
    }

    @Test
    void reportsInvalidGitHubResponseWithoutExposingItsBody() {
        wireMock.stubFor(get(urlPathEqualTo("/users/invalid-response/repos"))
            .willReturn(okJson("{not-valid-json")));

        given().when().get("/api/v1/metrics/invalid-response")
            .then().statusCode(502).contentType("application/problem+json")
            .body("title", is("GitHub response invalid"))
            .body("detail", not(containsString("not-valid-json")));
    }

    @Test
    void doesNotCacheGitHubFailures() {
        wireMock.stubFor(get(urlPathEqualTo("/repos/testowner/retry-after-failure/pulls"))
            .willReturn(aResponse().withStatus(503)));

        given().when().get("/api/v1/metrics/testowner/retry-after-failure")
            .then().statusCode(503);
        wireMock.stubFor(get(urlPathEqualTo("/repos/testowner/retry-after-failure/pulls"))
            .willReturn(okJson("[]")));
        given().when().get("/api/v1/metrics/testowner/retry-after-failure")
            .then().statusCode(200).body("stuckPrs", hasSize(0));
    }

    @Test
    void doesNotMapLocalNotFoundToGitHubError() {
        given().when().get("/not-an-api-route")
            .then().statusCode(404)
            .body(not(containsString("GitHub owner or repository not found")));
    }

    private void stubPage(String path, int page, String response) {
        wireMock.stubFor(get(urlPathEqualTo(path))
            .withQueryParam("per_page", equalTo("100"))
            .withQueryParam("page", equalTo(Integer.toString(page)))
            .willReturn(okJson(response)));
    }

    @Test
    @DisplayName("A Valkey cache miss fetches GitHub data and a hit avoids another request")
    void testValkeyCachePreventsDuplicateGitHubRequests() throws Exception {
        String old = Instant.now().minus(72, ChronoUnit.HOURS).toString();
        wireMock.stubFor(get(urlPathEqualTo("/repos/testowner/cached-repo/pulls"))
            .willReturn(okJson("""
                [{"id":1,"number":1,"title":"Stuck","created_at":"%s","user":{"login":"dev"}}]
                """.formatted(old))));
        wireMock.stubFor(get(urlPathEqualTo("/repos/testowner/cached-repo/pulls/1/reviews"))
            .willReturn(okJson("[]")));

        // First call: Cache Miss -> hits GitHub once
        String fetchedAt = given()
            .when().get("/api/v1/metrics/testowner/cached-repo")
            .then()
            .statusCode(200)
            .body("stuckPrs", hasSize(1))
            .body("stuckPrs[0].number", is(1))
            .extract().path("fetchedAt");
        assertNotNull(Instant.parse(fetchedAt));

        // Second call: Cache Hit -> served from Valkey
        given()
            .when().get("/api/v1/metrics/testowner/cached-repo")
            .then()
            .statusCode(200)
            .body("stuckPrs", hasSize(1))
            .body("stuckPrs[0].number", is(1))
            .body("fetchedAt", is(fetchedAt));

        String ttl = GitHubWiremockResource.getValkey()
            .execInContainer("valkey-cli", "--raw", "ttl", "gitpulse:v1:repo-metrics:testowner:cached-repo").getStdout().trim();
        long configuredSeconds = org.eclipse.microprofile.config.ConfigProvider.getConfig()
            .getValue("gitpulse.cache.ttl", Duration.class).toSeconds();
        String expectedTtl = System.getProperty("expected.cache.ttl.seconds");
        if (expectedTtl != null) {
            assertEquals(Long.parseLong(expectedTtl), configuredSeconds);
        }
        assertTrue(Long.parseLong(ttl) >= configuredSeconds - 10 && Long.parseLong(ttl) <= configuredSeconds);
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/repos/testowner/cached-repo/pulls")));
    }

    @Test
    void keepsCachedMetricsForDifferentRepositoriesSeparate() {
        String old = Instant.now().minus(72, ChronoUnit.HOURS).toString();
        wireMock.stubFor(get(urlPathEqualTo("/repos/testowner/cache-first/pulls"))
            .willReturn(okJson("[]")));
        wireMock.stubFor(get(urlPathEqualTo("/repos/testowner/cache-second/pulls"))
            .willReturn(okJson("""
                [{"id":2,"number":2,"title":"Stuck","created_at":"%s","user":{"login":"dev"}}]
                """.formatted(old))));
        wireMock.stubFor(get(urlPathEqualTo("/repos/testowner/cache-second/pulls/2/reviews"))
            .willReturn(okJson("[]")));

        given().when().get("/api/v1/metrics/testowner/cache-first")
            .then().statusCode(200).body("stuckPrs", hasSize(0));
        given().when().get("/api/v1/metrics/testowner/cache-second")
            .then().statusCode(200).body("stuckPrs.number", contains(2));
        given().when().get("/api/v1/metrics/testowner/cache-first")
            .then().statusCode(200).body("stuckPrs", hasSize(0));
        given().when().get("/api/v1/metrics/testowner/cache-second")
            .then().statusCode(200).body("stuckPrs.number", contains(2));

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/repos/testowner/cache-first/pulls")));
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/repos/testowner/cache-second/pulls")));
    }

    @Test
    void fetchesAgainWhenTheCacheEntryExpires() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/repos/testowner/expired-repo/pulls"))
            .willReturn(okJson("[]")));
        given().when().get("/api/v1/metrics/testowner/expired-repo").then().statusCode(200);
        assertEquals("1", GitHubWiremockResource.getValkey()
            .execInContainer("valkey-cli", "--raw", "expire",
                "gitpulse:v1:repo-metrics:testowner:expired-repo", "0").getStdout().trim());
        given().when().get("/api/v1/metrics/testowner/expired-repo").then().statusCode(200);
        wireMock.verify(2, getRequestedFor(urlPathEqualTo("/repos/testowner/expired-repo/pulls")));
    }

    @Test
    void fallsBackToGitHubWhenValkeyTimesOut() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/repos/testowner/cache-outage/pulls"))
            .willReturn(okJson("[]")));
        wireMock.stubFor(get(urlPathEqualTo("/repos/testowner/cache-outage-failed/pulls"))
            .willReturn(aResponse().withStatus(503)));
        GitHubWiremockResource.getValkey().execInContainer("valkey-cli", "CLIENT", "PAUSE", "5000", "ALL");
        try {
            given().when().get("/q/health/live").then().statusCode(200).body("status", is("UP"));
            given().when().get("/q/health/ready").then().statusCode(200).body("status", is("UP"));
            given().when().get("/api/v1/metrics/testowner/cache-outage")
                .then().statusCode(200).body("stuckPrs", hasSize(0)).body("fetchedAt", notNullValue());
            wireMock.verify(1, getRequestedFor(urlPathEqualTo("/repos/testowner/cache-outage/pulls")));
            given().when().get("/api/v1/metrics/testowner/cache-outage-failed")
                .then().statusCode(503).body("title", is("GitHub unavailable"));
            wireMock.verify(1, getRequestedFor(urlPathEqualTo("/repos/testowner/cache-outage-failed/pulls")));
        } finally {
            GitHubWiremockResource.getValkey().execInContainer("valkey-cli", "CLIENT", "UNPAUSE");
        }
    }

    @Test
    void exposesHttpMetricsForMonitoring() {
        given().when().get("/q/metrics")
            .then().statusCode(200).body(containsString("http_server_requests_seconds_count"));
    }

    @Test
    void returnsFreshDataWithoutRefetchingWhenCacheWriteTimesOut() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/repos/testowner/cache-write-outage/pulls"))
            .willReturn(okJson("[]")));
        GitHubWiremockResource.getValkey().execInContainer("valkey-cli", "CLIENT", "PAUSE", "5000", "WRITE");
        try {
            given().when().get("/api/v1/metrics/testowner/cache-write-outage")
                .then().statusCode(200).body("stuckPrs", hasSize(0)).body("fetchedAt", notNullValue());
            wireMock.verify(1, getRequestedFor(urlPathEqualTo("/repos/testowner/cache-write-outage/pulls")));
        } finally {
            GitHubWiremockResource.getValkey().execInContainer("valkey-cli", "CLIENT", "UNPAUSE");
        }
    }

    @Test
    void fetchesAgainIfCachedJsonIsInvalid() throws Exception {
        GitHubWiremockResource.getValkey().execInContainer("valkey-cli", "SET",
            "gitpulse:v1:repo-metrics:testowner:corrupt-cache", "invalid-json");
        wireMock.stubFor(get(urlPathEqualTo("/repos/testowner/corrupt-cache/pulls"))
            .willReturn(okJson("[]")));
        given().when().get("/api/v1/metrics/testowner/corrupt-cache")
            .then().statusCode(200).body("stuckPrs", hasSize(0));
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/repos/testowner/corrupt-cache/pulls")));
    }

}