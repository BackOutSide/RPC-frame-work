package io.github.backoutside.rivet.remoting.transport;

import io.github.backoutside.rivet.extension.SPI;
import io.github.backoutside.rivet.remoting.dto.RpcRequest;

/**
 * RPC请求传输接口
 */
@SPI
public interface RpcRequestTransport {
    /**
     * send rpc request to server and get result
     *
     * @param rpcRequest message body
     * @return data from server
     */
    Object sendRpcRequest(RpcRequest rpcRequest);
}
