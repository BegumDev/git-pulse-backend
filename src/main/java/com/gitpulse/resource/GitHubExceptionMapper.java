package com.gitpulse.resource;

import com.gitpulse.client.GitHubUpstreamException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

@Provider
public class GitHubExceptionMapper implements ExceptionMapper<GitHubUpstreamException> {

    @Override
    public Response toResponse(GitHubUpstreamException exception) {
        return Response.status(exception.status())
            .type("application/problem+json")
            .entity(Map.of(
                "type", "about:blank",
                "title", exception.title(),
                "status", exception.status(),
                "detail", exception.getMessage()
            ))
            .build();
    }
}
