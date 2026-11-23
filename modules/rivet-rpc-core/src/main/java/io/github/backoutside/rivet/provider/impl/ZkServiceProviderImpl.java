package io.github.backoutside.rivet.provider.impl;

import io.github.backoutside.rivet.config.RpcServiceConfig;
import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.enums.ServiceRegistryEnum;
import io.github.backoutside.rivet.exception.RpcException;
import io.github.backoutside.rivet.extension.ExtensionLoader;
import io.github.backoutside.rivet.factory.SingletonFactory;
import io.github.backoutside.rivet.provider.ServiceProvider;
import io.github.backoutside.rivet.registry.ServiceRegistry;
import io.github.backoutside.rivet.registry.zk.util.CuratorUtils;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author shuang.kou
 * @createTime 2020年05月13日 11:23:00
 */
@Slf4j
public class ZkServiceProviderImpl implements ServiceProvider {

    /**
     * key: rpc service name(interface name + version + group)
     * value: service object
     */

    // 本地缓存服务对象 建立RpcServiceName与服务对象的映射关系
    private final Map<String, Object> serviceMap;

    // 已注册的服务名称集合 用于避免重复注册
    private final Set<String> registeredService;

    // 服务注册接口 用于将服务注册到注册中心zookeeper
    private final ServiceRegistry serviceRegistry;
    private volatile InetSocketAddress publishedAddress;

    public static ZkServiceProviderImpl getInstance() {
        return SingletonFactory.getInstance(ZkServiceProviderImpl.class);
    }

    public ZkServiceProviderImpl() {
        serviceMap = new ConcurrentHashMap<>();
        registeredService = ConcurrentHashMap.newKeySet();
        serviceRegistry = ExtensionLoader.getExtensionLoader(ServiceRegistry.class).getExtension(ServiceRegistryEnum.ZK.getName());
    }

    /**
     * 添加本地服务
     *
     * @param rpcServiceConfig 服务配置
     */
    @Override
    public void addService(RpcServiceConfig rpcServiceConfig) {
        String rpcServiceName = rpcServiceConfig.getRpcServiceName();
        Object previous = serviceMap.putIfAbsent(rpcServiceName, rpcServiceConfig.getService());
        if (previous == null) {
            registeredService.add(rpcServiceName);
            log.info("Added RPC service: {}", rpcServiceName);
        } else if (previous != rpcServiceConfig.getService()) {
            throw new IllegalStateException("Duplicate RPC service: " + rpcServiceName);
        }
    }

    /**
     * 获取本地服务
     *
     * @param rpcServiceName 服务名称
     * @return 服务对象
     */
    @Override
    public Object getService(String rpcServiceName) {
        Object service = serviceMap.get(rpcServiceName);
        if (null == service) {
            throw new RpcException(RpcStatusCode.SERVICE_NOT_FOUND, null,
                    "RPC service not found", null);
        }
        return service;
    }

    /**
     * 发布zookeeper服务
     *
     */
    @Override
    public void publishService(RpcServiceConfig rpcServiceConfig) {
        addService(rpcServiceConfig);
        InetSocketAddress address = publishedAddress;
        if (address != null) {
            serviceRegistry.registerService(rpcServiceConfig.getRpcServiceName(), address);
        }
    }

    public boolean hasServices() {
        return !serviceMap.isEmpty();
    }

    /** Call only after the TCP listener has bound successfully. */
    public synchronized void registerAll(InetSocketAddress address) {
        if (publishedAddress != null) {
            return;
        }
        try {
            for (String serviceName : registeredService) {
                serviceRegistry.registerService(serviceName, address);
            }
            publishedAddress = address;
        } catch (RuntimeException ex) {
            CuratorUtils.clearRegistry(CuratorUtils.getZkClient(), address);
            throw ex;
        }
    }

    public synchronized void unregisterAll() {
        InetSocketAddress address = publishedAddress;
        publishedAddress = null;
        if (address != null) {
            CuratorUtils.clearRegistry(CuratorUtils.getZkClient(), address);
        }
    }

}
