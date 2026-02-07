package github.javaguide.registry;

import github.javaguide.extension.SPI;
import github.javaguide.remoting.dto.RpcRequest;

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
