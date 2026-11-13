package com.mipay.sme.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void missingPermissionIs403NotA500() {
        ProblemDetail pd = handler.accessDenied(new AccessDeniedException("Access Denied"));
        assertThat(pd.getStatus()).isEqualTo(403);
        assertThat(pd.getProperties()).containsEntry("code", "NO_PERMISSION");
    }

    @Test
    void unknownPathIs404NotA500() {
        ProblemDetail pd = handler.generic(new NoResourceFoundException(HttpMethod.GET, "api/orgs/x/users"));
        assertThat(pd.getStatus()).isEqualTo(404);
    }

    @Test
    void realFaultStays500WithoutLeakingTheMessage() {
        ProblemDetail pd = handler.generic(new IllegalStateException("db password=secret"));
        assertThat(pd.getStatus()).isEqualTo(500);
        assertThat(pd.getDetail()).doesNotContain("secret");
    }
}
