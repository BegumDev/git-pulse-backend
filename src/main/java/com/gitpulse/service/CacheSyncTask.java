package com.gitpulse.service;

import com.gitpulse.dao.TrackedRepositoryDao;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class CacheSyncTask {

    private static final Logger LOG = Logger.getLogger(CacheSyncTask.class);

    @Inject
    TrackedRepositoryDao repositoryDao;

    @Inject
    MetricsService metricsService;

    @Scheduled(every = "24h")
    public void refreshTrackedRepositories() {
        List<String[]> activeRepos = repositoryDao.findRecentlyAccessedRepos();
        LOG.infof("Running daily cache refresh for %d tracked repositories", activeRepos.size());

        for (String[] repoPair : activeRepos) {
            String owner = repoPair[0];
            String repo = repoPair[1];
            try {
                metricsService.invalidateMetricsCache(owner, repo);
                metricsService.invalidateBranchCache(owner, repo);
                metricsService.calculateMetrics(owner, repo);
                metricsService.calculateBranchHygiene(owner, repo);
                LOG.infof("Refreshed cache for %s/%s", owner, repo);
            } catch (Exception e) {
                LOG.errorf("Failed to refresh cache for %s/%s: %s", owner, repo, e.getMessage());
            }
        }
    }
}