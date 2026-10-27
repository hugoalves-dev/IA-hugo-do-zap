package dev.hugo.watcher.ai;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.function.Function;

final class FakeProviderTransport implements ProviderTransport {
    private final Function<HttpRequest, Response> responder;
    private final List<HttpRequest> requests = new ArrayList<>();

    FakeProviderTransport(Function<HttpRequest, Response> responder) { this.responder = responder; }

    @Override public Response send(HttpRequest request, Duration timeout, int maxResponseBytes) {
        requests.add(request);
        return responder.apply(request);
    }

    List<HttpRequest> requests() { return List.copyOf(requests); }

    static ProviderTransport.Response response(int status, String body) {
        return new ProviderTransport.Response(status, body);
    }

    static String requestBody(HttpRequest request) {
        CompletableFuture<byte[]> body = new CompletableFuture<>();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }
            @Override public void onError(Throwable error) { body.completeExceptionally(error); }
            @Override public void onComplete() { body.complete(bytes.toByteArray()); }
        });
        return new String(body.join(), StandardCharsets.UTF_8);
    }
}
