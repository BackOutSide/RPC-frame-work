package github.javaguide.loadbalance;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 记录客户端侧每个服务节点的活跃请求数。
 */
public class ActiveRequestsRecorder {

    // key: rpcServiceName, value: (key: serviceAddress, value: activeCount)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicInteger>> serviceActiveCountMap = new ConcurrentHashMap<>();

    public void increment(String rpcServiceName, String serviceAddress) {
        ConcurrentHashMap<String, AtomicInteger> addressActiveCountMap =
                serviceActiveCountMap.computeIfAbsent(rpcServiceName, k -> new ConcurrentHashMap<>());
        AtomicInteger activeCount = addressActiveCountMap.computeIfAbsent(serviceAddress, k -> new AtomicInteger(0));
        activeCount.incrementAndGet();
    }

    public void decrement(String rpcServiceName, String serviceAddress) {
        ConcurrentHashMap<String, AtomicInteger> addressActiveCountMap = serviceActiveCountMap.get(rpcServiceName);
        if (addressActiveCountMap == null) {
            return;
        }
        AtomicInteger activeCount = addressActiveCountMap.get(serviceAddress);
        if (activeCount == null) {
            return;
        }
        int current = activeCount.decrementAndGet();
        if (current <= 0) {
            addressActiveCountMap.remove(serviceAddress, activeCount);
            if (addressActiveCountMap.isEmpty()) {
                serviceActiveCountMap.remove(rpcServiceName, addressActiveCountMap);
            }
        }
    }

    public int getActiveCount(String rpcServiceName, String serviceAddress) {
        ConcurrentHashMap<String, AtomicInteger> addressActiveCountMap = serviceActiveCountMap.get(rpcServiceName);
        if (addressActiveCountMap == null) {
            return 0;
        }
        AtomicInteger activeCount = addressActiveCountMap.get(serviceAddress);
        return activeCount == null ? 0 : Math.max(activeCount.get(), 0);
    }
}
