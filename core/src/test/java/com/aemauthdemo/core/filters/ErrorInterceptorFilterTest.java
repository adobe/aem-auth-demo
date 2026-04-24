/*
 *  Copyright 2025 Adobe Systems Incorporated
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.aemauthdemo.core.filters;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import com.aemauthdemo.core.testcontext.AppAemContext;
import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({AemContextExtension.class, MockitoExtension.class})
class ErrorInterceptorFilterTest {

    private static final String PROTECTED_PATH = "/content/test/oauth2-path";
    private static final String ERROR_PAGE     = "/content/test/errors/500.html";
    private static final String OAUTH_MSG      = "No request cookie named sling.oauth-request-key found";

    private final AemContext context = AppAemContext.newAemContext();
    private ErrorInterceptorFilter filter;

    @BeforeEach
    void setup() {
        Map<String, Object> props = new HashMap<>();
        props.put("enabled", true);
        props.put("path.pattern", PROTECTED_PATH);
        props.put("redirect.path", ERROR_PAGE);
        props.put("error.messages", new String[]{"oauth", OAUTH_MSG});
        filter = context.registerInjectActivateService(new ErrorInterceptorFilter(), props);
    }

    private HttpServletRequest request(String uri) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn(uri);
        return req;
    }

    // ── Pass-through ──────────────────────────────────────────────────────────

    @Test
    void passThrough_whenPathDoesNotMatch() throws IOException, ServletException {
        HttpServletRequest req = request("/content/other/page.html");
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        // Chain receives the original (unwrapped) response
        verify(chain).doFilter(req, res);
    }

    @Test
    void passThrough_whenFilterIsDisabled() throws IOException, ServletException {
        Map<String, Object> props = new HashMap<>();
        props.put("enabled", false);
        ErrorInterceptorFilter disabledFilter =
            context.registerInjectActivateService(new ErrorInterceptorFilter(), props);

        HttpServletRequest req = request(PROTECTED_PATH + "/page.html");
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        disabledFilter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
    }

    // ── OAuth error → redirect ────────────────────────────────────────────────

    @Test
    void redirect_toErrorPage_whenBufferedSendError_containsOAuthMessage() throws IOException, ServletException {
        HttpServletRequest req = request(PROTECTED_PATH + "/callback");
        HttpServletResponse res = mock(HttpServletResponse.class);
        when(res.isCommitted()).thenReturn(false);

        FilterChain chain = (rq, rs) -> ((HttpServletResponse) rs).sendError(500, OAUTH_MSG);

        filter.doFilter(req, res, chain);

        verify(res).sendRedirect(ERROR_PAGE);
        verify(res, never()).sendError(anyInt(), anyString());
    }

    @Test
    void redirect_toErrorPage_whenIllegalStateException_containsOAuthMessage() throws IOException, ServletException {
        HttpServletRequest req = request(PROTECTED_PATH + "/callback");
        HttpServletResponse res = mock(HttpServletResponse.class);
        when(res.isCommitted()).thenReturn(false);

        FilterChain chain = (rq, rs) -> { throw new IllegalStateException(OAUTH_MSG); };

        filter.doFilter(req, res, chain);

        verify(res).sendRedirect(ERROR_PAGE);
    }

    // ── Non-OAuth error → propagate ───────────────────────────────────────────

    @Test
    void propagateError_whenSendError_doesNotMatchOAuthMessages() throws IOException, ServletException {
        HttpServletRequest req = request(PROTECTED_PATH + "/page.html");
        HttpServletResponse res = mock(HttpServletResponse.class);
        when(res.isCommitted()).thenReturn(false);

        FilterChain chain = (rq, rs) -> ((HttpServletResponse) rs).sendError(500, "Unrelated server error");

        filter.doFilter(req, res, chain);

        verify(res).sendError(500, "Unrelated server error");
        verify(res, never()).sendRedirect(anyString());
    }

    @Test
    void rethrow_whenIllegalStateException_doesNotMatchOAuthMessages() throws IOException, ServletException {
        HttpServletRequest req = request(PROTECTED_PATH + "/page.html");
        HttpServletResponse res = mock(HttpServletResponse.class);

        FilterChain chain = (rq, rs) -> { throw new IllegalStateException("Something unrelated"); };

        assertThrows(IllegalStateException.class, () -> filter.doFilter(req, res, chain));
    }

    // ── Regression: resetBuffer() must not reach the underlying committed response ──

    /**
     * Felix HTTP InvocationChain.doFilter calls resetBuffer() on the response
     * before invoking sendError(). Before the fix, BufferedResponseWrapper delegated
     * resetBuffer() to the underlying Jetty response, which threw if already committed,
     * producing "IllegalStateException: Committed" in the log and swallowing the OAuth
     * error detection.
     */
    @Test
    void noThrow_whenFilterChainCallsResetBufferThenSendError_andUnderlyingWouldThrow()
            throws IOException, ServletException {
        HttpServletRequest req = request(PROTECTED_PATH + "/callback");
        HttpServletResponse res = mock(HttpServletResponse.class);
        when(res.isCommitted()).thenReturn(false);
        // Underlying response simulates a committed state — resetBuffer() would throw.
        // lenient() because the assertion is that this stub is NEVER reached (verify never()).
        lenient().doThrow(new IllegalStateException("Committed")).when(res).resetBuffer();

        // Reproduces what Felix HTTP InvocationChain.doFilter does internally
        FilterChain chain = (rq, rs) -> {
            HttpServletResponse r = (HttpServletResponse) rs;
            r.resetBuffer(); // hits BufferedResponseWrapper — must NOT reach res
            r.sendError(500, OAUTH_MSG);
        };

        // Must not throw; OAuth error must still be redirected
        filter.doFilter(req, res, chain);

        verify(res).sendRedirect(ERROR_PAGE);
        verify(res, never()).resetBuffer(); // wrapper absorbed the call, never delegated
    }

    // ── Regression: committed-response guards prevent double-write ────────────

    @Test
    void noRedirectOrError_whenUnderlyingResponseAlreadyCommitted_afterOAuthError()
            throws IOException, ServletException {
        HttpServletRequest req = request(PROTECTED_PATH + "/callback");
        HttpServletResponse res = mock(HttpServletResponse.class);
        when(res.isCommitted()).thenReturn(true);

        FilterChain chain = (rq, rs) -> ((HttpServletResponse) rs).sendError(500, OAUTH_MSG);

        // Must not throw; filter logs a warning and stops
        filter.doFilter(req, res, chain);

        verify(res, never()).sendRedirect(anyString());
        verify(res, never()).sendError(anyInt(), anyString());
    }

    @Test
    void noRedirectOrError_whenUnderlyingResponseAlreadyCommitted_afterNonOAuthError()
            throws IOException, ServletException {
        HttpServletRequest req = request(PROTECTED_PATH + "/page.html");
        HttpServletResponse res = mock(HttpServletResponse.class);
        when(res.isCommitted()).thenReturn(true);

        FilterChain chain = (rq, rs) -> ((HttpServletResponse) rs).sendError(500, "Unrelated error");

        filter.doFilter(req, res, chain);

        verify(res, never()).sendRedirect(anyString());
        verify(res, never()).sendError(anyInt(), anyString());
    }
}
