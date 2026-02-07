package github.javaguide.registry;

import github.javaguide.extension.SPI;

import java.net.InetSocketAddress;

/**
 *  服务注册接口
 *
 */
@SPI
public interface ServiceRegistry {
    /**
     * 注册服务
     *
     * @param rpcServiceName    rpc service name 服务名称
     * @param inetSocketAddress service address 服务地址
     */
    void registerService(String rpcServiceName, InetSocketAddress inetSocketAddress);

}
