package dev.hugo.watcher;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Component
public class WebhookBodyLimitFilter extends OncePerRequestFilter {
    private static final String WEBHOOK_PATH = "/internal/evolution/webhook";
    private static final String ARCHIVE_MODE_PATH = "/internal/archive-mode";
    private final EvolutionWebhookProperties properties;

    public WebhookBodyLimitFilter(EvolutionWebhookProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && WEBHOOK_PATH.equals(request.getRequestURI())
                || "PUT".equals(request.getMethod()) && ARCHIVE_MODE_PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int max = ARCHIVE_MODE_PATH.equals(request.getRequestURI()) ? 256 : properties.getMaxBodyBytes();
        if (request.getContentLengthLong() > max) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(max + 1);
        if (body.length > max) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        chain.doFilter(new BoundedBodyRequest(request, body), response);
    }

    private static final class BoundedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private BoundedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
