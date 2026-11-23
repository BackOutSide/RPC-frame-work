package io.github.backoutside.rivet.remoting.transport.netty.client;

import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.exception.RpcException;
import io.github.backoutside.rivet.exception.RpcUnavailableException;
import io.github.backoutside.rivet.remoting.dto.RpcResponse;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void typedChannelFailureRetainsEachPendingRequestId() {
        UnprocessedRequests requests = new UnprocessedRequests();
        EmbeddedChannel channel = new EmbeddedChannel();
        CompletableFuture<RpcResponse<Object>> first = new CompletableFuture<>();
        CompletableFuture<RpcResponse<Object>> second = new CompletableFuture<>();
        requests.put("request-a", channel, first);
        requests.put("request-b", channel, second);

        requests.failChannel(channel, RpcStatusCode.PROTOCOL_ERROR, "bad RPC frame", null);

        RpcException firstFailure = assertInstanceOf(RpcException.class,
                assertThrows(CompletionException.class, first::join).getCause());
        RpcException secondFailure = assertInstanceOf(RpcException.class,
                assertThrows(CompletionException.class, second::join).getCause());
        assertEquals(RpcStatusCode.PROTOCOL_ERROR.getCode(), firstFailure.getCode());
        assertEquals("request-a", firstFailure.getRequestId());
        assertEquals("request-b", secondFailure.getRequestId());
        assertEquals(0, requests.size());
        channel.finishAndReleaseAll();
    }

    @Test
    void inactiveChannelIsReportedAsConnectionLost() {
        UnprocessedRequests requests = new UnprocessedRequests();
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.close();
        CompletableFuture<RpcResponse<Object>> future = new CompletableFuture<>();

        requests.put("request-closed", channel, future);

        RpcUnavailableException failure = assertInstanceOf(RpcUnavailableException.class,
                assertThrows(CompletionException.class, future::join).getCause());
        assertEquals(RpcStatusCode.CONNECTION_LOST.getCode(), failure.getCode());
        assertEquals("request-closed", failure.getRequestId());
        assertEquals(0, requests.size());
        channel.finishAndReleaseAll();
    }
}
