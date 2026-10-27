package dev.hugo.watcher.ai;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** HTTPS-only production transport with bounded response collection and an end-to-end deadline. */
public final class JdkProviderTransport implements ProviderTransport {
    private final AsyncHttpExchange exchange;
    private final HttpClient.Redirect redirectPolicy;

    public JdkProviderTransport() {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        this.exchange = client::sendAsync;
        this.redirectPolicy = client.followRedirects();
    }

    JdkProviderTransport(AsyncHttpExchange exchange) {
        this.exchange = java.util.Objects.requireNonNull(exchange, "exchange");
        this.redirectPolicy = HttpClient.Redirect.NEVER;
    }

    HttpClient.Redirect redirectPolicy() { return redirectPolicy; }

    @Override public Response send(HttpRequest request, Duration timeout, int maxResponseBytes) {
        if (timeout == null || timeout.isZero() || timeout.isNegative() || maxResponseBytes < 1)
            throw new IllegalArgumentException("positive timeout and body limit are required");
        ProviderEndpointPolicy.requireSafe(request.uri());
        AtomicReference<BoundedBodySubscriber> subscriberRef = new AtomicReference<>();
        CompletableFuture<HttpResponse<byte[]>> pending = exchange.sendAsync(request, info -> {
            BoundedBodySubscriber subscriber = new BoundedBodySubscriber(maxResponseBytes);
            subscriberRef.set(subscriber);
            return subscriber;
        });
        try {
            HttpResponse<byte[]> response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (response.previousResponse().isPresent() || !request.uri().equals(response.uri()))
                throw new ClassificationProviderException(ClassificationProviderException.Kind.CONFIGURATION_ERROR);
            return new Response(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        } catch (TimeoutException exception) {
            cancel(pending, subscriberRef.get());
            throw new ClassificationProviderException(ClassificationProviderException.Kind.TECHNICAL_FAILURE, exception);
        } catch (InterruptedException exception) {
            cancel(pending, subscriberRef.get());
            Thread.currentThread().interrupt();
            throw new ClassificationProviderException(ClassificationProviderException.Kind.TECHNICAL_FAILURE, exception);
        } catch (ExecutionException exception) {
            Throwable cause = rootCause(exception);
            cancel(pending, subscriberRef.get());
            if (cause instanceof ResponseTooLargeException)
                throw new ClassificationProviderException(ClassificationProviderException.Kind.RESPONSE_TOO_LARGE, cause);
            throw new ClassificationProviderException(ClassificationProviderException.Kind.TECHNICAL_FAILURE, cause);
        } catch (java.util.concurrent.CancellationException exception) {
            cancel(pending, subscriberRef.get());
            throw new ClassificationProviderException(ClassificationProviderException.Kind.TECHNICAL_FAILURE, exception);
        }
    }

    private static void cancel(CompletableFuture<?> pending, BoundedBodySubscriber subscriber) {
        if (subscriber != null) subscriber.cancel();
        pending.cancel(true);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while ((current instanceof ExecutionException || current instanceof CompletionException) && current.getCause() != null)
            current = current.getCause();
        for (Throwable cause = current; cause != null; cause = cause.getCause())
            if (cause instanceof ResponseTooLargeException) return cause;
        return current;
    }

    interface AsyncHttpExchange {
        CompletableFuture<HttpResponse<byte[]>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<byte[]> bodyHandler);
    }

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

        private BoundedBodySubscriber(int limit) { this.limit = limit; }

        @Override public java.util.concurrent.CompletionStage<byte[]> getBody() { return body; }

        @Override public void onSubscribe(Flow.Subscription value) {
            if (!subscription.compareAndSet(null, value)) {
                value.cancel();
                return;
            }
            value.request(1);
        }

        @Override public void onNext(List<ByteBuffer> chunks) {
            long incoming = chunks.stream().mapToLong(ByteBuffer::remaining).sum();
            if (incoming > limit - bytes.size()) {
                Flow.Subscription current = subscription.get();
                if (current != null) current.cancel();
                body.completeExceptionally(new ResponseTooLargeException());
                return;
            }
            for (ByteBuffer chunk : chunks) {
                ByteBuffer copy = chunk.asReadOnlyBuffer();
                byte[] part = new byte[copy.remaining()];
                copy.get(part);
                bytes.writeBytes(part);
            }
            Flow.Subscription current = subscription.get();
            if (current != null && !body.isDone()) current.request(1);
        }

        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }

        private void cancel() {
            Flow.Subscription current = subscription.get();
            if (current != null) current.cancel();
            body.cancel(true);
        }
    }

    private static final class ResponseTooLargeException extends RuntimeException { }
}
