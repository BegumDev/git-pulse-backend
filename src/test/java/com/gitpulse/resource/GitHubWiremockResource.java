package com.gitpulse.resource;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

public class GitHubWiremockResource implements QuarkusTestResourceLifecycleManager {

    private static WireMockServer wireMockServer;
    private static GenericContainer<?> valkey;
    private String previousRedisHosts;

    @Override
    public Map<String, String> start() {
        previousRedisHosts = System.getProperty("quarkus.redis.hosts");
        valkey = new GenericContainer<>(DockerImageName.parse("docker.io/valkey/valkey:8.0.2-alpine"))
            .withExposedPorts(6379);
        try {
            valkey.start();
            String redisHosts = "redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379);
            System.setProperty("quarkus.redis.hosts", redisHosts);
            wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
            wireMockServer.start();
            return Map.of(
                "quarkus.redis.hosts", redisHosts,
                "quarkus.rest-client.github-api.url", wireMockServer.baseUrl(),
                "quarkus.rest-client.github-api.read-timeout", "500",
                "github.token", "test-dummy-token"
            );
        } catch (RuntimeException | Error e) {
            stop();
            throw e;
        }
    }

    @Override
    public void stop() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
        if (previousRedisHosts == null) {
            System.clearProperty("quarkus.redis.hosts");
        } else {
            System.setProperty("quarkus.redis.hosts", previousRedisHosts);
        }
        if (valkey != null) {
            valkey.stop();
        }
    }

    public static WireMockServer getServer() {
        return wireMockServer;
    }

    public static GenericContainer<?> getValkey() {
        return valkey;
    }
}