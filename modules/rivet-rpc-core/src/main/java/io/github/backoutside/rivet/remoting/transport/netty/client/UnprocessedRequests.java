package io.github.backoutside.rivet.remoting.transport.netty.client;

import io.github.backoutside.rivet.exception.RpcUnavailableException;
import io.github.backoutside.rivet.remoting.dto.RpcResponse;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Correlates responses and fails all in-flight requests when a channel dies. */
public class UnprocessedRequests {
    private final Map<String, PendingRequest> pending = new ConcurrentHashMap<>();
    private final Map<ChannelId, Set<String>> channelRequests = new ConcurrentHashMap<>();

    public void put(String requestId, Channel channel, CompletableFuture<RpcResponse<Object>> future) {
        PendingRequest request = new PendingRequest(channel, future);
        if (pending.putIfAbsent(requestId, request) != null) {
            throw new IllegalStateException("Duplicate RPC request ID: " + requestId);
        }
        channelRequests.computeIfAbsent(channel.id(), ignored -> ConcurrentHashMap.newKeySet()).add(requestId);
        if (!channel.isActive()) {
            failChannel(channel, new RpcUnavailableException("RPC channel closed before send"));
        }
    }

    public void remove(String requestId) {
        PendingRequest request = pending.remove(requestId);
        if (request != null) {
            removeFromChannel(requestId, request.channel);
        }
    }

    public void complete(RpcResponse<Object> response) {
        if (response == null || response.getRequestId() == null) {
            return;
        }
        PendingRequest request = pending.remove(response.getRequestId());
        if (request != null) {
            removeFromChannel(response.getRequestId(), request.channel);
            request.future.complete(response);
        }
        // A response arriving after a client deadline is expected and can be ignored.
    }

    public void failChannel(Channel channel, Throwable cause) {
        Set<String> requestIds = channelRequests.remove(channel.id());
        if (requestIds == null) {
            return;
        }
        for (String requestId : requestIds) {
            PendingRequest request = pending.remove(requestId);
            if (request != null) {
                request.future.completeExceptionally(cause);
            }
        }
    }

    public int size() {
        return pending.size();
    }

    private void removeFromChannel(String requestId, Channel channel) {
        Set<String> requestIds = channelRequests.get(channel.id());
        if (requestIds != null) {
            requestIds.remove(requestId);
        }
    }

    private static final class PendingRequest {
        private final Channel channel;
        private final CompletableFuture<RpcResponse<Object>> future;

        private PendingRequest(Channel channel, CompletableFuture<RpcResponse<Object>> future) {
            this.channel = channel;
            this.future = future;
        }
    }
}
