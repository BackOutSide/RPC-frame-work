package io.github.backoutside.rivet.registry.zk;

import io.github.backoutside.rivet.config.RpcConfiguration;
import io.github.backoutside.rivet.enums.RpcStatusCode;
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
import java.util.ArrayList;
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
        return lookupServices(rpcRequest).get(0);
    }

    @Override
    public List<InetSocketAddress> lookupServices(RpcRequest rpcRequest) {
        String rpcServiceName = rpcRequest.getRpcServiceName();
        String requestId = rpcRequest.getRequestId();
        List<String> serviceUrlList;
        try {
            CuratorFramework zkClient = CuratorUtils.getZkClient();
            serviceUrlList = CuratorUtils.getChildrenNodes(zkClient, rpcServiceName);
        } catch (RuntimeException ex) {
            throw new RpcUnavailableException(RpcStatusCode.DISCOVERY_FAILED, requestId,
                    "RPC service discovery failed for " + rpcServiceName, ex);
        }
        if (CollectionUtil.isEmpty(serviceUrlList)) {
            throw new RpcUnavailableException(RpcStatusCode.NO_PROVIDER, requestId,
                    "No RPC provider for " + rpcServiceName, null);
        }
        List<String> remaining = new ArrayList<>(serviceUrlList);
        List<InetSocketAddress> candidates = new ArrayList<>(remaining.size());
        try {
            while (!remaining.isEmpty()) {
                String selected = loadBalance.selectServiceAddress(remaining, rpcRequest);
                remaining.remove(selected);
                candidates.add(parseAddress(selected));
            }
        } catch (RuntimeException ex) {
            throw new RpcUnavailableException(RpcStatusCode.DISCOVERY_FAILED, requestId,
                    "Invalid RPC provider selection for " + rpcServiceName, ex);
        }
        return candidates;
    }

    static InetSocketAddress parseAddress(String value) {
        int separator = value.lastIndexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new RpcUnavailableException(RpcStatusCode.DISCOVERY_FAILED, null,
                    "Invalid RPC provider address: " + value, null);
        }
        String host = value.substring(0, separator);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        try {
            int port = Integer.parseInt(value.substring(separator + 1));
            if (port < 1 || port > 65535) {
                throw new NumberFormatException("port out of range");
            }
            return new InetSocketAddress(host, port);
        } catch (NumberFormatException ex) {
            throw new RpcUnavailableException(RpcStatusCode.DISCOVERY_FAILED, null,
                    "Invalid RPC provider address: " + value, ex);
        }
    }
}
