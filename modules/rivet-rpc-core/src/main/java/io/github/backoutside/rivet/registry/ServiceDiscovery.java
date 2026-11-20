package io.github.backoutside.rivet.registry;

import io.github.backoutside.rivet.extension.SPI;
import io.github.backoutside.rivet.remoting.dto.RpcRequest;

import java.net.InetSocketAddress;

/**
 * 服务发现接口
 * 
 */
@SPI
public interface ServiceDiscovery {
    /**
     * 根据服务名称查找服务地址
     *
     * @param rpcRequest Rpc请求
     * @return service address
     */
    InetSocketAddress lookupService(RpcRequest rpcRequest);
}
