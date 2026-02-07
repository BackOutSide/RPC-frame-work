package github.javaguide.loadbalance.loadbalancer;

import github.javaguide.factory.SingletonFactory;
import github.javaguide.loadbalance.AbstractLoadBalance;
import github.javaguide.loadbalance.ActiveRequestsRecorder;
import github.javaguide.remoting.dto.RpcRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 最少活跃数负载均衡:
 * 优先选择当前活跃请求数最少的节点。
 */
public class LeastActiveLoadBalance extends AbstractLoadBalance {

    private final ActiveRequestsRecorder activeRequestsRecorder = SingletonFactory.getInstance(ActiveRequestsRecorder.class);
    private final ConcurrentHashMap<String, AtomicInteger> sequenceMap = new ConcurrentHashMap<>();

    @Override
    protected String doSelect(List<String> serviceAddresses, RpcRequest rpcRequest) {
        String rpcServiceName = rpcRequest.getRpcServiceName();
        int leastActive = Integer.MAX_VALUE;
        List<String> leastActiveAddresses = new ArrayList<>();

        for (String serviceAddress : serviceAddresses) {
            int active = activeRequestsRecorder.getActiveCount(rpcServiceName, serviceAddress);
            if (active < leastActive) {
                leastActive = active;
                leastActiveAddresses.clear();
                leastActiveAddresses.add(serviceAddress);
            } else if (active == leastActive) {
                leastActiveAddresses.add(serviceAddress);
            }
        }

        if (leastActiveAddresses.size() == 1) {
            return leastActiveAddresses.get(0);
        }

        AtomicInteger sequence = sequenceMap.computeIfAbsent(rpcServiceName, k -> new AtomicInteger(0));
        int index = Math.floorMod(sequence.getAndIncrement(), leastActiveAddresses.size());
        return leastActiveAddresses.get(index);
    }
}
