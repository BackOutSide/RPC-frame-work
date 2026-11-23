package io.github.backoutside.rivet.registry.zk.util;

import io.github.backoutside.rivet.config.RpcConfiguration;
import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.exception.RpcUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.framework.recipes.nodes.PersistentNode;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Curator(zookeeper client) 工具类
 *
 */
@Slf4j
public final class CuratorUtils {

    // 基础睡眠时间
    private static final int BASE_SLEEP_TIME = 1000;
    // 最大重试次数
    private static final int MAX_RETRIES = 3;
    // 注册根路径
    public static final String ZK_REGISTER_ROOT_PATH = "/my-rpc";
    // A successful ZooKeeper read is the only operation that renews a snapshot.
    private static final Map<String, AddressSnapshot> SERVICE_ADDRESS_MAP = new ConcurrentHashMap<>();
    private static final Map<String, PathChildrenCache> WATCHERS = new ConcurrentHashMap<>();
    // PersistentNode retains the desired ephemeral registration across session loss.
    private static final Map<String, PersistentNode> REGISTERED_NODES = new ConcurrentHashMap<>();
    // Zookeeper客户端
    private static CuratorFramework zkClient;
    // 默认Zookeeper地址

    private static final Counter REFRESH_FAILURES = Metrics.counter("rivet.rpc.registry.refresh.failures");

    static {
        Gauge.builder("rivet.rpc.registry.cache.age", SERVICE_ADDRESS_MAP, snapshots -> {
                    long now = System.nanoTime();
                    return snapshots.values().stream()
                            .mapToDouble(snapshot -> Math.max(0, now - snapshot.refreshedAtNanos) / 1_000_000_000.0)
                            .max().orElse(0);
                })
                .baseUnit("seconds")
                .register(Metrics.globalRegistry);
    }

    /**
     * 构造函数
     */
    private CuratorUtils() {
    }

    /**
     * 创建持久化节点
     *
     * @param path node path
     */
    public static void createPersistentNode(CuratorFramework zkClient, String path) {
        try {
            if (zkClient.checkExists().forPath(path) != null) {
                log.info("The node already exists. The node is:[{}]", path);
            } else {
                //eg: /my-rpc/io.github.backoutside.rivet.HelloService/127.0.0.1:9999
                zkClient.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(path);
                log.info("The node was created successfully. The node is:[{}]", path);
            }
        } catch (Exception e) {
            log.error("create persistent node for path [{}] fail", path);
        }
    }

    /**
     * 获取节点下的子节点
     *
     * @param rpcServiceName rpc service name eg:io.github.backoutside.rivet.HelloServicetest2version1
     * @return All child nodes under the specified node
     */
    public static List<String> getChildrenNodes(CuratorFramework zkClient, String rpcServiceName) {
        AddressSnapshot cached = SERVICE_ADDRESS_MAP.get(rpcServiceName);
        if (isFresh(cached)) {
            return cached.addresses;
        }
        String servicePath = ZK_REGISTER_ROOT_PATH + "/" + rpcServiceName;
        if (!zkClient.getZookeeperClient().isConnected()) {
            REFRESH_FAILURES.increment();
            throw discoveryFailure(rpcServiceName,
                    "ZooKeeper is disconnected and no fresh provider snapshot is available", null);
        }
        AddressSnapshot refreshed = null;
        try {
            List<String> result = List.copyOf(zkClient.getChildren().forPath(servicePath));
            refreshed = new AddressSnapshot(result);
            SERVICE_ADDRESS_MAP.put(rpcServiceName, refreshed);
            registerWatcher(rpcServiceName, zkClient);
            return result;
        } catch (KeeperException.NoNodeException ex) {
            // A missing service path is a successful lookup with no providers.
            AddressSnapshot obsolete = refreshed != null ? refreshed : cached;
            if (obsolete != null) {
                SERVICE_ADDRESS_MAP.remove(rpcServiceName, obsolete);
            }
            return List.of();
        } catch (Exception e) {
            REFRESH_FAILURES.increment();
            AddressSnapshot obsolete = refreshed != null ? refreshed : cached;
            if (obsolete != null) {
                SERVICE_ADDRESS_MAP.remove(rpcServiceName, obsolete);
            }
            throw discoveryFailure(rpcServiceName, "Could not refresh RPC provider snapshot", e);
        }
    }

    private static boolean isFresh(AddressSnapshot snapshot) {
        return snapshot != null && System.nanoTime() - snapshot.refreshedAtNanos
                < TimeUnit.MILLISECONDS.toNanos(RpcConfiguration.current().getZkCacheMaxStaleMs());
    }

    private static RpcUnavailableException discoveryFailure(String service, String message, Throwable cause) {
        return new RpcUnavailableException(RpcStatusCode.DISCOVERY_FAILED, null,
                message + " for " + service, cause);
    }

    /**
     * 清空注册地址
     */
    public static void clearRegistry(CuratorFramework zkClient, InetSocketAddress inetSocketAddress) {
        String host = inetSocketAddress.getHostString();
        String address = (host.contains(":") ? "[" + host + "]" : host) + ":" + inetSocketAddress.getPort();
        synchronized (REGISTERED_NODES) {
            for (String path : new ArrayList<>(REGISTERED_NODES.keySet())) {
                if (path.endsWith("/" + address)) {
                    closeRegistration(path, REGISTERED_NODES.remove(path));
                }
            }
        }
    }

    /**
     * 获取Zookeeper客户端
     * @return
     */
    public static CuratorFramework getZkClient() {
        CuratorFramework client = zkClient;
        if (client != null && client.getState() == CuratorFrameworkState.STARTED) {
            return client;
        }
        synchronized (CuratorUtils.class) {
            client = zkClient;
            if (client != null && client.getState() == CuratorFrameworkState.STARTED) {
                return client;
            }
            RetryPolicy retryPolicy = new ExponentialBackoffRetry(BASE_SLEEP_TIME, MAX_RETRIES);
            client = CuratorFrameworkFactory.builder()
                    .connectString(RpcConfiguration.current().getZkAddress())
                    .retryPolicy(retryPolicy)
                    .build();
            client.start();
            try {
                if (!client.blockUntilConnected(RpcConfiguration.current().getConnectTimeoutMs(), TimeUnit.MILLISECONDS)) {
                    throw new RpcUnavailableException("ZooKeeper connection timed out");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                client.close();
                throw new RpcUnavailableException("Interrupted while connecting to ZooKeeper", ex);
            } catch (RuntimeException ex) {
                client.close();
                throw ex;
            }
            zkClient = client;
            return client;
        }
    }

    public static synchronized void closeZkClient() {
        for (PathChildrenCache watcher : WATCHERS.values()) {
            try {
                watcher.close();
            } catch (IOException ex) {
                log.warn("Could not close service watcher", ex);
            }
        }
        WATCHERS.clear();
        SERVICE_ADDRESS_MAP.clear();
        synchronized (REGISTERED_NODES) {
            for (Map.Entry<String, PersistentNode> entry : REGISTERED_NODES.entrySet()) {
                closeRegistration(entry.getKey(), entry.getValue());
            }
            REGISTERED_NODES.clear();
        }
        CuratorFramework client = zkClient;
        zkClient = null;
        if (client != null) {
            client.close();
        }
    }

    private static void closeRegistration(String path, PersistentNode node) {
        if (node == null) {
            return;
        }
        try {
            node.close();
        } catch (IOException ex) {
            log.warn("Could not close RPC registration {}", path, ex);
        }
    }

    /**
     * 监听指定节点的变化
     *
     * @param rpcServiceName rpc service name eg:io.github.backoutside.rivet.HelloServicetest2version
     */
    private static void registerWatcher(String rpcServiceName, CuratorFramework zkClient) throws Exception {
        String servicePath = ZK_REGISTER_ROOT_PATH + "/" + rpcServiceName;
        PathChildrenCache pathChildrenCache = new PathChildrenCache(zkClient, servicePath, true);
        PathChildrenCacheListener pathChildrenCacheListener = (curatorFramework, pathChildrenCacheEvent) -> {
            if (!curatorFramework.getZookeeperClient().isConnected()) {
                return;
            }
            try {
                List<String> serviceAddresses = List.copyOf(curatorFramework.getChildren().forPath(servicePath));
                SERVICE_ADDRESS_MAP.put(rpcServiceName, new AddressSnapshot(serviceAddresses));
            } catch (KeeperException.NoNodeException ex) {
                SERVICE_ADDRESS_MAP.put(rpcServiceName, new AddressSnapshot(List.of()));
            } catch (Exception ex) {
                // Retain the last known addresses only until max-stale-ms elapses.
                REFRESH_FAILURES.increment();
                log.warn("Could not refresh RPC providers for {} after ZooKeeper event", rpcServiceName, ex);
            }
        };
        pathChildrenCache.getListenable().addListener(pathChildrenCacheListener);
        PathChildrenCache existing = WATCHERS.putIfAbsent(rpcServiceName, pathChildrenCache);
        if (existing != null) {
            pathChildrenCache.close();
            return;
        }
        try {
            pathChildrenCache.start();
        } catch (Exception ex) {
            WATCHERS.remove(rpcServiceName, pathChildrenCache);
            pathChildrenCache.close();
            throw ex;
        }
    }
    /**
     * 创建临时结点
     * */
    public static void createEphemeralNode(CuratorFramework zkClient, String path) {
        synchronized (REGISTERED_NODES) {
            if (REGISTERED_NODES.containsKey(path)) {
                return;
            }
            PersistentNode node = new PersistentNode(zkClient, CreateMode.EPHEMERAL, false,
                    path, "status:ok".getBytes(StandardCharsets.UTF_8), true);
            try {
                node.start();
                if (!node.waitForInitialCreate(RpcConfiguration.current().getConnectTimeoutMs(),
                        TimeUnit.MILLISECONDS)) {
                    throw new RpcUnavailableException(RpcStatusCode.DISCOVERY_FAILED, null,
                            "Timed out registering RPC service " + path, null);
                }
                REGISTERED_NODES.put(path, node);
                log.info("Registered RPC service {} with managed ephemeral node", path);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                closeRegistration(path, node);
                throw new RpcUnavailableException(RpcStatusCode.DISCOVERY_FAILED, null,
                        "Interrupted registering RPC service " + path, ex);
            } catch (RuntimeException ex) {
                closeRegistration(path, node);
                throw ex instanceof RpcUnavailableException ? ex
                        : new RpcUnavailableException(RpcStatusCode.DISCOVERY_FAILED, null,
                                "Could not register RPC service " + path, ex);
            }
        }
    }

    public static void deleteEphemeralNode(CuratorFramework zkClient, String path) {
        synchronized (REGISTERED_NODES) {
            PersistentNode node = REGISTERED_NODES.remove(path);
            if (node != null) {
                closeRegistration(path, node);
                return;
            }
        }
        try {
            zkClient.delete().deletingChildrenIfNeeded().forPath(path);
        } catch (Exception e) {
            log.error("删除临时结点：{}失败", path);
        }
    }

    private static final class AddressSnapshot {
        private final List<String> addresses;
        private final long refreshedAtNanos;

        private AddressSnapshot(List<String> addresses) {
            this.addresses = addresses;
            this.refreshedAtNanos = System.nanoTime();
        }
    }
}
