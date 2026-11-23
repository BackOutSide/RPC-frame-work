package io.github.backoutside.rivet.registry.zk;

import io.github.backoutside.rivet.registry.ServiceRegistry;
import io.github.backoutside.rivet.registry.zk.util.CuratorUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;

import java.net.InetSocketAddress;

/**
 * 基于Zookeeper的服务注册实现实现类
 *
 */
@Slf4j
public class ZkServiceRegistryImpl implements ServiceRegistry {

    /**
     * 注册服务
     * @param rpcServiceName 服务名称
     * @param inetSocketAddress 服务地址 
     */
    @Override
    public void registerService(String rpcServiceName, InetSocketAddress inetSocketAddress) {
        String host = inetSocketAddress.getHostString();
        String address = (host.contains(":") ? "[" + host + "]" : host) + ":" + inetSocketAddress.getPort();
        String servicePath = CuratorUtils.ZK_REGISTER_ROOT_PATH + "/" + rpcServiceName + "/" + address;
        CuratorFramework zkClient = CuratorUtils.getZkClient();
        CuratorUtils.createEphemeralNode(zkClient, servicePath);
    }
}
