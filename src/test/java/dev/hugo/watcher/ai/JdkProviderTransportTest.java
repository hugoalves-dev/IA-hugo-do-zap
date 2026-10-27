package dev.hugo.watcher.ai;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class JdkProviderTransportTest {
    private static final URI ENDPOINT = URI.create("https://openrouter.ai/api/v1/chat/completions");

    @Test void bodyDeadlineCancelsRequestWhenHeadersArriveButBodyNeverDoes() {
        var exchange = new FakeExchange(false);
        var transport = new JdkProviderTransport(exchange);
        var request = HttpRequest.newBuilder(ENDPOINT).GET().build();

        var error = assertThrows(ClassificationProviderException.class,
                () -> transport.send(request, Duration.ofMillis(150), 64 * 1024));

        assertEquals(ClassificationProviderException.Kind.TECHNICAL_FAILURE, error.kind());
        assertTrue(exchange.subscriptionCancelled.get());
        assertTrue(exchange.futureCancelled.get());
    }

    @Test void oversizedBodyCancelsSubscriptionAndFailsClosed() {
        var exchange = new FakeExchange(true);
        var transport = new JdkProviderTransport(exchange);
        var request = HttpRequest.newBuilder(ENDPOINT).GET().build();

        var error = assertThrows(ClassificationProviderException.class,
                () -> transport.send(request, Duration.ofSeconds(1), 32));

        assertEquals("RESPONSE_TOO_LARGE", error.kind().name());
        assertTrue(exchange.subscriptionCancelled.get());
    }

    @Test void productionClientNeverFollowsRedirects() {
        assertEquals(HttpClient.Redirect.NEVER, new JdkProviderTransport().redirectPolicy());
    }

    private static final class FakeExchange implements JdkProviderTransport.AsyncHttpExchange {
        private final boolean oversized;
        private final AtomicBoolean subscriptionCancelled = new AtomicBoolean();
        private final AtomicBoolean futureCancelled = new AtomicBoolean();
        private final AtomicReference<HttpResponse.BodySubscriber<byte[]>> subscriber = new AtomicReference<>();

        private FakeExchange(boolean oversized) { this.oversized = oversized; }

        @Override public CompletableFuture<HttpResponse<byte[]>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<byte[]> bodyHandler) {
            HttpResponse.BodySubscriber<byte[]> body = bodyHandler.apply(new ResponseInfo());
            subscriber.set(body);
            body.onSubscribe(new Flow.Subscription() {
                @Override public void request(long count) {
                    if (oversized) body.onNext(List.of(ByteBuffer.wrap(new byte[33])));
                }
                @Override public void cancel() { subscriptionCancelled.set(true); }
            });
            CompletableFuture<HttpResponse<byte[]>> response = new CompletableFuture<>();
            body.getBody().whenComplete((bytes, failure) -> {
                if (failure != null) response.completeExceptionally(failure);
                else response.complete(new CompletedResponse(request, bytes));
            });
            response.whenComplete((ignored, failure) -> {
                if (response.isCancelled()) futureCancelled.set(true);
            });
            return response;
        }
    }

    private static final class ResponseInfo implements HttpResponse.ResponseInfo {
        @Override public int statusCode() { return 200; }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, value) -> true); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    private record CompletedResponse(HttpRequest request, byte[] body) implements HttpResponse<byte[]> {
        @Override public int statusCode() { return 200; }
        @Override public HttpRequest request() { return request; }
        @Override public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, value) -> true); }
        @Override public byte[] body() { return body; }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
