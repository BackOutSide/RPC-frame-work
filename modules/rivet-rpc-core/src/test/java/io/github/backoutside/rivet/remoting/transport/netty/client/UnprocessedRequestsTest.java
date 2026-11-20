package io.github.backoutside.rivet.remoting.transport.netty.client;

import io.github.backoutside.rivet.remoting.dto.RpcResponse;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnprocessedRequestsTest {
    @Test
    void disconnectFailsPendingRequestAndLateReplyIsIgnored() {
        UnprocessedRequests requests = new UnprocessedRequests();
        EmbeddedChannel channel = new EmbeddedChannel();
        CompletableFuture<RpcResponse<Object>> future = new CompletableFuture<>();
        requests.put("request-1", channel, future);

        requests.failChannel(channel, new IllegalStateException("disconnected"));
        assertTrue(future.isCompletedExceptionally());
        assertEquals(0, requests.size());
        requests.complete(RpcResponse.success("late", "request-1"));
        assertEquals(0, requests.size());
        channel.finishAndReleaseAll();
    }

    @Test
    void responseCompletesTheMatchingRequest() {
        UnprocessedRequests requests = new UnprocessedRequests();
        EmbeddedChannel channel = new EmbeddedChannel();
        CompletableFuture<RpcResponse<Object>> future = new CompletableFuture<>();
        requests.put("request-2", channel, future);

        requests.complete(RpcResponse.success("ok", "request-2"));
        assertEquals("ok", future.join().getData());
        assertEquals(0, requests.size());
        channel.finishAndReleaseAll();
    }
}
