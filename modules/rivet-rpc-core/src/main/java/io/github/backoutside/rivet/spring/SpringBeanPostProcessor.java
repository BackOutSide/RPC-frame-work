package io.github.backoutside.rivet.spring;

import io.github.backoutside.rivet.annotation.RpcReference;
import io.github.backoutside.rivet.annotation.RpcService;
import io.github.backoutside.rivet.config.RpcConfiguration;
import io.github.backoutside.rivet.config.RpcServiceConfig;
import io.github.backoutside.rivet.enums.RpcRequestTransportEnum;
import io.github.backoutside.rivet.extension.ExtensionLoader;
import io.github.backoutside.rivet.provider.impl.ZkServiceProviderImpl;
import io.github.backoutside.rivet.proxy.RpcClientProxy;
import io.github.backoutside.rivet.registry.zk.util.CuratorUtils;
import io.github.backoutside.rivet.remoting.transport.RpcRequestTransport;
import io.github.backoutside.rivet.remoting.transport.netty.client.NettyRpcClient;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;

/** Injects RPC proxies and exports fully initialized service beans. */
@Component
public class SpringBeanPostProcessor implements BeanPostProcessor, EnvironmentAware, Ordered, DisposableBean {
    private final ZkServiceProviderImpl serviceProvider = ZkServiceProviderImpl.getInstance();
    private volatile RpcRequestTransport rpcClient;

    @Override
    public int getOrder() {
        // Export after Spring's auto-proxy creator so @Transactional is retained.
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void setEnvironment(Environment environment) {
        RpcConfiguration.configure(environment);
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        // Inject into the target before an AOP proxy may wrap it.
        for (Class<?> type = bean.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                RpcReference reference = field.getAnnotation(RpcReference.class);
                if (reference == null) {
                    continue;
                }
                if (!field.getType().isInterface()) {
                    throw new IllegalStateException("@RpcReference requires an interface: " + field);
                }
                RpcServiceConfig config = RpcServiceConfig.builder()
                        .group(reference.group()).version(reference.version()).build();
                Object proxy = new RpcClientProxy(getRpcClient(), config).getProxy(field.getType());
                try {
                    field.setAccessible(true);
                    field.set(bean, proxy);
                } catch (IllegalAccessException ex) {
                    throw new IllegalStateException("Could not inject RPC reference " + field, ex);
                }
            }
        }
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        RpcService annotation = AnnotationUtils.findAnnotation(targetClass, RpcService.class);
        if (annotation != null) {
            Class<?> serviceInterface = annotation.interfaceClass();
            if (serviceInterface == void.class) {
                Class<?>[] interfaces = targetClass.getInterfaces();
                if (interfaces.length == 0) {
                    throw new IllegalStateException("@RpcService must implement an interface: " + targetClass.getName());
                }
                serviceInterface = interfaces[0];
            }
            if (!serviceInterface.isInterface() || !serviceInterface.isAssignableFrom(targetClass)) {
                throw new IllegalStateException("Invalid RPC interface " + serviceInterface.getName()
                        + " for " + targetClass.getName());
            }
            serviceProvider.publishService(RpcServiceConfig.builder()
                    .group(annotation.group()).version(annotation.version())
                    .service(bean).interfaceClass(serviceInterface).build());
        }
        return bean;
    }

    private RpcRequestTransport getRpcClient() {
        RpcRequestTransport client = rpcClient;
        if (client == null) {
            synchronized (this) {
                client = rpcClient;
                if (client == null) {
                    client = ExtensionLoader.getExtensionLoader(RpcRequestTransport.class)
                            .getExtension(RpcRequestTransportEnum.NETTY.getName());
                    rpcClient = client;
                }
            }
        }
        return client;
    }

    @Override
    public void destroy() {
        RpcRequestTransport client = rpcClient;
        if (client instanceof NettyRpcClient) {
            ((NettyRpcClient) client).close();
        }
        CuratorUtils.closeZkClient();
    }
}
