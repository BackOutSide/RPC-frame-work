package github.javaguide.loadbalance.loadbalancer;

import github.javaguide.loadbalance.AbstractLoadBalance;
import github.javaguide.remoting.dto.RpcRequest;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implementation of round robin load balancing strategy
 * 轮询负载均衡算法：按顺序依次选择服务节点
 *
 * @author jinwu
 * @createTime 2026年02月07日
 */
public class RoundRobinLoadBalance extends AbstractLoadBalance {

    // 使用ConcurrentHashMap存储每个服务的轮询计数器
    private final ConcurrentHashMap<String, AtomicInteger> positionMap = new ConcurrentHashMap<>();

    /**
     * 选择服务地址
     * @param serviceAddresses 服务地址列表
     * @param rpcRequest 请求
     * @return 选择的服务地址
     */
    @Override
    protected String doSelect(List<String> serviceAddresses, RpcRequest rpcRequest) {
        String rpcServiceName = rpcRequest.getRpcServiceName();

        // 获取或创建该服务的计数器
        AtomicInteger position = positionMap.computeIfAbsent(rpcServiceName, k -> new AtomicInteger(0));

        // 获取当前位置并递增，使用取模确保不越界
        int currentPosition = position.getAndIncrement() % serviceAddresses.size();

        // 防止position无限增长，当超过Integer.MAX_VALUE的一半时重置
        if (position.get() > Integer.MAX_VALUE / 2) {
            position.set(0);
        }

        return serviceAddresses.get(currentPosition);
    }
}