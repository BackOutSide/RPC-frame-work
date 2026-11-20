package io.github.backoutside.rivet.loadbalance.loadbalancer;

import io.github.backoutside.rivet.loadbalance.AbstractLoadBalance;
import io.github.backoutside.rivet.remoting.dto.RpcRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Consistent hashing with an immutable ring snapshot for each RPC service. */
public class ConsistentHashLoadBalanceNew extends AbstractLoadBalance {
    private static final int VIRTUAL_NODES = 160;
    private final ConcurrentHashMap<String, Ring> rings = new ConcurrentHashMap<>();

    // Kept for the existing diagnostic tests.
    public static final AtomicInteger count = new AtomicInteger();
    public static final AtomicInteger createCount = new AtomicInteger();

    @Override
    protected String doSelect(List<String> addresses, RpcRequest request) {
        String serviceName = request.getRpcServiceName();
        Ring ring = rings.get(serviceName);
        if (ring == null || !ring.matches(addresses)) {
            Ring candidate = new Ring(addresses);
            ring = rings.compute(serviceName, (name, existing) -> {
                if (existing != null && existing.matches(addresses)) {
                    return existing;
                }
                if (existing == null) {
                    createCount.incrementAndGet();
                } else {
                    count.incrementAndGet();
                }
                return candidate;
            });
        }
        return ring.select(serviceName + request.getRequestId());
    }

    private static final class Ring {
        private final Set<String> addresses;
        private final NavigableMap<Long, String> nodes;

        private Ring(List<String> addresses) {
            this.addresses = new HashSet<>(addresses);
            NavigableMap<Long, String> map = new TreeMap<>();
            for (String address : this.addresses) {
                for (int index = 0; index < VIRTUAL_NODES; index++) {
                    map.put(hash(address + '#' + index), address);
                }
            }
            this.nodes = map;
        }

        private boolean matches(List<String> current) {
            return addresses.size() == current.size() && addresses.containsAll(current);
        }

        private String select(String key) {
            Map.Entry<Long, String> entry = nodes.ceilingEntry(hash(key));
            return entry == null ? nodes.firstEntry().getValue() : entry.getValue();
        }

        private static long hash(String value) {
            try {
                MessageDigest digest = MessageDigest.getInstance("MD5");
                byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
                long result = 0;
                for (int index = 0; index < 8; index++) {
                    result = (result << 8) | (bytes[index] & 0xffL);
                }
                return result;
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("MD5 is unavailable", ex);
            }
        }
    }
}
