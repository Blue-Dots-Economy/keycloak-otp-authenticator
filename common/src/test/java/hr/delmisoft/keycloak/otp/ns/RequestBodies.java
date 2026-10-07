package hr.delmisoft.keycloak.otp.ns;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

final class RequestBodies {
    private RequestBodies() {}

    static byte[] of(HttpRequest request) throws Exception {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompletableFuture<Void> done = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                out.writeBytes(chunk);
            }
            @Override public void onError(Throwable t) { done.completeExceptionally(t); }
            @Override public void onComplete() { done.complete(null); }
        });
        done.get();
        return out.toByteArray();
    }
}
