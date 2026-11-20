package io.github.backoutside.rivet.registry.zk.util;

import io.github.backoutside.rivet.config.RpcConfiguration;
import io.github.backoutside.rivet.exception.RpcUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    // 服务地址映射
    private static final Map<String, List<String>> SERVICE_ADDRESS_MAP = new ConcurrentHashMap<>();
    private static final Map<String, PathChildrenCache> WATCHERS = new ConcurrentHashMap<>();
    // 注册路径集合 
    private static final Set<String> REGISTERED_PATH_SET = ConcurrentHashMap.newKeySet();
    // 会话失效后需要重注册
    private static final AtomicBoolean NEED_REREGISTER = new AtomicBoolean(false);
    // Zookeeper客户端
    private static CuratorFramework zkClient;
    // 默认Zookeeper地址

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
            if (REGISTERED_PATH_SET.contains(path) || zkClient.checkExists().forPath(path) != null) {
                log.info("The node already exists. The node is:[{}]", path);
            } else {
                //eg: /my-rpc/io.github.backoutside.rivet.HelloService/127.0.0.1:9999
                zkClient.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(path);
                log.info("The node was created successfully. The node is:[{}]", path);
            }
            REGISTERED_PATH_SET.add(path);
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
        if (SERVICE_ADDRESS_MAP.containsKey(rpcServiceName)) {
            return SERVICE_ADDRESS_MAP.get(rpcServiceName);
        }
        List<String> result = null;
        String servicePath = ZK_REGISTER_ROOT_PATH + "/" + rpcServiceName;
        try {
            result = zkClient.getChildren().forPath(servicePath);
            SERVICE_ADDRESS_MAP.put(rpcServiceName, result);
            registerWatcher(rpcServiceName, zkClient);
        } catch (Exception e) {
            log.error("get children nodes for path [{}] fail", servicePath);
        }
        return result;
    }

    /**
     * 清空注册地址
     */
    public static void clearRegistry(CuratorFramework zkClient, InetSocketAddress inetSocketAddress) {
        for (String p : REGISTERED_PATH_SET) {
            try {
                if (p.endsWith(inetSocketAddress.toString())) {
                    zkClient.delete().forPath(p);
                    REGISTERED_PATH_SET.remove(p);
                }
            } catch (Exception e) {
                log.error("clear registry for path [{}] fail", p);
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
            addConnectionStateListener(client);
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
        REGISTERED_PATH_SET.clear();
        CuratorFramework client = zkClient;
        zkClient = null;
        if (client != null) {
            client.close();
        }
    }

    private static void addConnectionStateListener(CuratorFramework zkClient) {
        zkClient.getConnectionStateListenable().addListener((client, newState) -> {
            if (newState == ConnectionState.LOST) {
                NEED_REREGISTER.set(true);
                log.warn("Zookeeper session lost, will re-register ephemeral nodes after reconnect.");
                return;
            }
            if (newState == ConnectionState.RECONNECTED && NEED_REREGISTER.compareAndSet(true, false)) {
                reRegisterEphemeralNodes(client);
            }
        });
    }

    private static void reRegisterEphemeralNodes(CuratorFramework zkClient) {
        for (String path : REGISTERED_PATH_SET) {
            try {
                createEphemeralNode(zkClient, path);
            } catch (RuntimeException ex) {
                log.error("Could not re-register RPC service {}", path, ex);
            }
        }
        log.info("Re-registered services after reconnect, paths: [{}]", REGISTERED_PATH_SET);
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
            List<String> serviceAddresses = curatorFramework.getChildren().forPath(servicePath);
            SERVICE_ADDRESS_MAP.put(rpcServiceName, serviceAddresses);
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
        try {
            if (zkClient.checkExists().forPath(path) != null) {
                log.info("The node already exists. The node is:[{}]", path);
            } else {
                // 创建临时节点（EPHEMERAL 模式）
                zkClient.create()
                        .creatingParentsIfNeeded()
                        .withMode(CreateMode.EPHEMERAL)  // 指定为临时节点
                        .withACL(org.apache.zookeeper.ZooDefs.Ids.OPEN_ACL_UNSAFE)  // 设置ACL
                        .forPath(path, "status:ok".getBytes());  // 指定节点路径和数据
                log.info("The ephemeral node was created successfully. The node is:[{}]", path);
            }
            REGISTERED_PATH_SET.add(path);
        } catch (Exception e) {
            log.error("创建临时结点失败!", e);
            throw new RpcUnavailableException("Could not register RPC service " + path, e);
        }
    }
    public static void deleteEphemeralNode(CuratorFramework zkClient, String path) {
        // 方法2：强制删除节点（无论是否有子节点）
        try {
            zkClient.delete().deletingChildrenIfNeeded().forPath(path);
        } catch (Exception e) {
            log.error("删除临时结点：{}失败", path);
        }
    }
}
