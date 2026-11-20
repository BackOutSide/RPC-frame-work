package io.github.backoutside.rivet.registry.zk;

import io.github.backoutside.rivet.config.RpcConfiguration;
import io.github.backoutside.rivet.exception.RpcUnavailableException;
import io.github.backoutside.rivet.loadbalance.LoadBalance;
import io.github.backoutside.rivet.loadbalance.loadbalancer.RandomLoadBalance;
import io.github.backoutside.rivet.loadbalance.loadbalancer.RoundRobinLoadBalance;
import io.github.backoutside.rivet.registry.ServiceDiscovery;
import io.github.backoutside.rivet.registry.zk.util.CuratorUtils;
import io.github.backoutside.rivet.remoting.dto.RpcRequest;
import io.github.backoutside.rivet.utils.CollectionUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * 基于Zookeeper的服务发现实现
 *
 */
@Slf4j
public class ZkServiceDiscoveryImpl implements ServiceDiscovery {
    private final LoadBalance loadBalance;

    public ZkServiceDiscoveryImpl() {
        String strategy = RpcConfiguration.current().getLoadBalance();
        if ("random".equalsIgnoreCase(strategy)) {
            this.loadBalance = new RandomLoadBalance();
        } else if ("roundRobin".equalsIgnoreCase(strategy) || "round-robin".equalsIgnoreCase(strategy)) {
            this.loadBalance = new RoundRobinLoadBalance();
        } else {
            throw new IllegalArgumentException("Unsupported RPC load balance strategy: " + strategy);
        }
    }

    @Override
    public InetSocketAddress lookupService(RpcRequest rpcRequest) {
        String rpcServiceName = rpcRequest.getRpcServiceName();
        CuratorFramework zkClient = CuratorUtils.getZkClient();
        List<String> serviceUrlList = CuratorUtils.getChildrenNodes(zkClient, rpcServiceName);
        if (CollectionUtil.isEmpty(serviceUrlList)) {
            throw new RpcUnavailableException("No RPC provider for " + rpcServiceName);
        }
        // load balancing
        String targetServiceUrl = loadBalance.selectServiceAddress(serviceUrlList, rpcRequest);
        log.info("Successfully found the service address:[{}]", targetServiceUrl);
        String[] socketAddressArray = targetServiceUrl.split(":");
        String host = socketAddressArray[0];
        int port = Integer.parseInt(socketAddressArray[1]);
        return new InetSocketAddress(host, port);
    }
}
