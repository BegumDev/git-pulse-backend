package com.gitpulse.resource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusIntegrationTest
@QuarkusTestResource(GitHubWiremockResource.class)
@DisplayName("GitPulse End-to-End Packaged JAR Integration Tests")
class MetricsResourceIT {

    @BeforeEach
    void resetWireMock() {
        GitHubWiremockResource.getServer().resetAll();
    }

    @Test
    @DisplayName("IT-01: End-to-end PR detection and Valkey cache hit")
    void testEndToEndPrMetricsAndCaching() {
        String olderThan48h = Instant.now().minus(60, ChronoUnit.HOURS).toString();

        GitHubWiremockResource.getServer().stubFor(get(urlPathEqualTo("/repos/it-org/core-api/pulls"))
            .willReturn(okJson("""
                [
                  {
                    "id": 9001,
                    "number": 77,
                    "title": "Fix database connection leak",
                    "created_at": "%s",
                    "head": { "ref": "fix/db-leak" },
                    "user": { "login": "lead-dev" }
                  }
                ]
                """.formatted(olderThan48h))));

        GitHubWiremockResource.getServer().stubFor(get(urlPathEqualTo("/repos/it-org/core-api/pulls/77/reviews"))
            .willReturn(okJson("[]")));

        // 1st request: Hits WireMock and populates Valkey.
        given()
            .when().get("/api/v1/metrics/it-org/core-api")
            .then()
            .statusCode(200)
            .body("owner", is("it-org"))
            .body("repo", is("core-api"))
            .body("stuckPrs", hasSize(1))
            .body("stuckPrs[0].number", is(77))
            .body("stuckPrs[0].author", is("lead-dev"));

        // 2nd request: Served from Valkey in the packaged artifact.
        given()
            .when().get("/api/v1/metrics/it-org/core-api")
            .then()
            .statusCode(200)
            .body("stuckPrs", hasSize(1));

        GitHubWiremockResource.getServer()
            .verify(1, getRequestedFor(urlPathEqualTo("/repos/it-org/core-api/pulls")));
    }

    @Test
    @DisplayName("IT-02: End-to-end stale branch hygiene audit and owner repository discovery against packaged JAR")
    void testEndToEndBranchHygieneAndOwnerLookup() {
        String staleDate = Instant.now().minus(40, ChronoUnit.DAYS).toString();
        String freshDate = Instant.now().minus(1, ChronoUnit.DAYS).toString();

        GitHubWiremockResource.getServer().stubFor(get(urlPathEqualTo("/users/it-org/repos"))
            .willReturn(okJson("""
                [
                  { "name": "core-api", "description": "Main backend service" }
                ]
                """)));

        GitHubWiremockResource.getServer().stubFor(get(urlPathEqualTo("/repos/it-org/core-api"))
            .willReturn(okJson("{\"id\": 123, \"default_branch\": \"main\"}")));
        GitHubWiremockResource.getServer().stubFor(get(urlPathEqualTo("/repos/it-org/core-api/branches"))
            .willReturn(okJson("""
                [
                  { "name": "main" },
                  { "name": "legacy-v1" }
                ]
                """)));

        GitHubWiremockResource.getServer().stubFor(get(urlPathEqualTo("/repos/it-org/core-api/pulls"))
            .willReturn(okJson("[]")));

        GitHubWiremockResource.getServer().stubFor(get(urlEqualTo("/repos/it-org/core-api/branches/main"))
            .willReturn(okJson("""
                { "name": "main", "commit": { "commit": { "committer": { "date": "%s" } } } }
                """.formatted(freshDate))));

        GitHubWiremockResource.getServer().stubFor(get(urlEqualTo("/repos/it-org/core-api/branches/legacy-v1"))
            .willReturn(okJson("""
                { "name": "legacy-v1", "commit": { "commit": { "committer": { "date": "%s" } } } }
                """.formatted(staleDate))));

        // Verify owner discovery
        given()
            .when().get("/api/v1/metrics/it-org")
            .then()
            .statusCode(200)
            .body("$", hasSize(1))
            .body("[0].name", is("core-api"));

        // Verify stale branch calculation
        given()
            .when().get("/api/v1/metrics/it-org/core-api/stale-branches")
            .then()
            .statusCode(200)
            .body("totalBranches", is(2))
            .body("staleBranches", is(1))
            .body("branches.find { it.name == 'legacy-v1' }.isStale", is(true));
    }

    @Test
    @DisplayName("IT-03: End-to-end 404 exception mapping in packaged JAR")
    void testEndToEndNotFoundMapping() {
        GitHubWiremockResource.getServer().stubFor(get(urlPathEqualTo("/repos/it-org/ghost-repo/pulls"))
            .willReturn(aResponse().withStatus(404)));

        given()
            .when().get("/api/v1/metrics/it-org/ghost-repo")
            .then()
            .statusCode(404)
            .body("status", is(404))
            .contentType("application/problem+json")
            .body("detail", is("GitHub owner or repository not found."));
    }
}