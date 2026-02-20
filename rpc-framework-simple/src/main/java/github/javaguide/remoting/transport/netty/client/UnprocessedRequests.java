package github.javaguide.remoting.transport.netty.client;

import github.javaguide.remoting.dto.RpcResponse;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 存储还未到达的请求
 * 使用CompletableFuture存储未处理请求，当请求到达时，CompletableFuture完成
 */
public class UnprocessedRequests {
    // 未处理请求 用于存储未处理请求
    private static final Map<String, CompletableFuture<RpcResponse<Object>>> UNPROCESSED_RESPONSE_FUTURES = new ConcurrentHashMap<>();

    /**
     * 存储还未到达请求
     * @param requestId
     * @param future
     */
    public void put(String requestId, CompletableFuture<RpcResponse<Object>> future) {
        UNPROCESSED_RESPONSE_FUTURES.put(requestId, future);
    }

    /**
     * 移除未处理请求，发送失败等场景使用
     */ 
    public void remove(String requestId) {
        UNPROCESSED_RESPONSE_FUTURES.remove(requestId);
    }

    /**
     * 完成请求 将请求从未处理请求中移除
     */
    public void complete(RpcResponse<Object> rpcResponse) {
        CompletableFuture<RpcResponse<Object>> future = UNPROCESSED_RESPONSE_FUTURES.remove(rpcResponse.getRequestId());
        if (null != future) {
            future.complete(rpcResponse);
        } else {
            throw new IllegalStateException();
        }
    }
}
