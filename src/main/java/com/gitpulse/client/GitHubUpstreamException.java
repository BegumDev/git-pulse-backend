package com.gitpulse.client;

public class GitHubUpstreamException extends RuntimeException {
    private final int status;
    private final String title;

    public GitHubUpstreamException(int status, String title, String detail) {
        super(detail);
        this.status = status;
        this.title = title;
    }

    public int status() {
        return status;
    }

    public String title() {
        return title;
    }
}
