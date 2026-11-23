package io.github.backoutside.rivet.remoting.transport.netty.client;

import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.exception.RpcException;
import io.github.backoutside.rivet.factory.SingletonFactory;
import io.github.backoutside.rivet.remoting.constants.RpcConstants;
import io.github.backoutside.rivet.remoting.dto.RpcMessage;
import io.github.backoutside.rivet.remoting.dto.RpcResponse;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NettyRpcClientHandlerStatusTest {
    private final UnprocessedRequests requests = SingletonFactory.getInstance(UnprocessedRequests.class);

    @Test
    void wrappedProtocolErrorFailsPendingCallWithItsId() {
        EmbeddedChannel channel = new EmbeddedChannel(new NettyRpcClientHandler());
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<RpcResponse<Object>> pending = new CompletableFuture<>();
        requests.put(requestId, channel, pending);

        channel.pipeline().fireExceptionCaught(new DecoderException(
                new RpcException(RpcStatusCode.PROTOCOL_ERROR, null, "bad magic", null)));

        RpcException failure = assertInstanceOf(RpcException.class,
                assertThrows(CompletionException.class, pending::join).getCause());
        assertEquals(RpcStatusCode.PROTOCOL_ERROR.getCode(), failure.getCode());
        assertEquals(requestId, failure.getRequestId());
        channel.finishAndReleaseAll();
    }

    @Test
    void responseWithoutIdFailsPendingCallImmediately() {
        EmbeddedChannel channel = new EmbeddedChannel(new NettyRpcClientHandler());
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<RpcResponse<Object>> pending = new CompletableFuture<>();
        requests.put(requestId, channel, pending);

        channel.writeInbound(RpcMessage.builder().messageType(RpcConstants.RESPONSE_TYPE)
                .data(RpcResponse.success("result", null)).build());

        RpcException failure = assertInstanceOf(RpcException.class,
                assertThrows(CompletionException.class, pending::join).getCause());
        assertEquals(RpcStatusCode.INVALID_RESPONSE.getCode(), failure.getCode());
        assertEquals(requestId, failure.getRequestId());
        channel.finishAndReleaseAll();
    }

    @Test
    void requestTypeFromProviderIsProtocolError() {
        EmbeddedChannel channel = new EmbeddedChannel(new NettyRpcClientHandler());
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<RpcResponse<Object>> pending = new CompletableFuture<>();
        requests.put(requestId, channel, pending);

        channel.writeInbound(RpcMessage.builder().messageType(RpcConstants.REQUEST_TYPE)
                .data("unexpected").build());

        RpcException failure = assertInstanceOf(RpcException.class,
                assertThrows(CompletionException.class, pending::join).getCause());
        assertEquals(RpcStatusCode.PROTOCOL_ERROR.getCode(), failure.getCode());
        assertEquals(requestId, failure.getRequestId());
        channel.finishAndReleaseAll();
    }
}
