package com.gitpulse.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.SetArgs;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Locale;
import java.util.function.Supplier;

@ApplicationScoped
public class MetricsCache {
    private static final Logger LOG = Logger.getLogger(MetricsCache.class);

    @Inject
    RedisDataSource redis;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "gitpulse.cache.ttl")
    Duration ttl;

    @PostConstruct
    void validateTtl() {
        if (ttl.toMillis() <= 0) {
            throw new IllegalArgumentException("gitpulse.cache.ttl must be at least one millisecond");
        }
    }

    public <T> T getOrFetch(String type, String owner, String repo, Class<T> resultType, Supplier<T> fetch) {
        String key = "gitpulse:v1:" + type + ":" + owner.toLowerCase(Locale.ROOT) + ":" + repo.toLowerCase(Locale.ROOT);
        String cached;
        try {
            cached = redis.value(String.class).get(key);
        } catch (RuntimeException e) {
            LOG.warn("Valkey read failed; fetching data from GitHub");
            return fetch.get();
        }
        if (cached != null) {
            try {
                T result = mapper.readValue(cached, resultType);
                if (result != null) {
                    return result;
                }
                LOG.warn("Valkey entry is empty; fetching data from GitHub");
            } catch (JsonProcessingException e) {
                LOG.warn("Valkey entry is invalid; fetching data from GitHub");
            }
        }

        T result = fetch.get();
        String json;
        try {
            json = mapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize metrics for caching", e);
        }
        try {
            redis.value(String.class).set(key, json, new SetArgs().px(ttl));
        } catch (RuntimeException e) {
            LOG.warn("Valkey write failed; returning freshly fetched data");
        }
        return result;
    }
}
