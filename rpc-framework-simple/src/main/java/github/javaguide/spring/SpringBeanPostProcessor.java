package github.javaguide.spring;

import github.javaguide.annotation.RpcReference;
import github.javaguide.annotation.RpcService;
import github.javaguide.config.RpcServiceConfig;
import github.javaguide.enums.RpcRequestTransportEnum;
import github.javaguide.extension.ExtensionLoader;
import github.javaguide.factory.SingletonFactory;
import github.javaguide.provider.ServiceProvider;
import github.javaguide.provider.impl.ZkServiceProviderImpl;
import github.javaguide.proxy.RpcClientProxy;
import github.javaguide.remoting.transport.RpcRequestTransport;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;

/**
 * call this method before creating the bean to see if the class is annotated
 *
 */
@Slf4j
@Component
public class SpringBeanPostProcessor implements BeanPostProcessor {
    
    /**
     * 服务提供者
     */
    private final ServiceProvider serviceProvider;

    /**
     * 客户端
     */
    private final RpcRequestTransport rpcClient;

    /**
     * 初始化服务提供者和客户端
     * 
     * 利用单例模式创建服务提供者
     * 
     * 利用扩展类加载器创建客户端
     * 
     */
    public SpringBeanPostProcessor() {
        this.serviceProvider = SingletonFactory.getInstance(ZkServiceProviderImpl.class);
        this.rpcClient = ExtensionLoader.getExtensionLoader(RpcRequestTransport.class).getExtension(RpcRequestTransportEnum.NETTY.getName());
    }

    /**
     * 对带有@RpcSerivce注解的类进行代理，直接将类发布到ZK上
     * 
     * 注意是在初始化之前，将类注册到zk上
     * 
     */
    @SneakyThrows
    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        // 1. 判断bean是否带有@RpcService注解
        if (bean.getClass().isAnnotationPresent(RpcService.class)) {
            log.info("[{}] is annotated with  [{}]", bean.getClass().getName(), RpcService.class.getCanonicalName());
            // 2. 获取@RpcService注解
            RpcService rpcService = bean.getClass().getAnnotation(RpcService.class);
            // 3. 构建RpcServiceConfig
            RpcServiceConfig rpcServiceConfig = RpcServiceConfig.builder()
                    .group(rpcService.group())
                    .version(rpcService.version())
                    .service(bean).build();
            // 4. 发布服务
            serviceProvider.publishService(rpcServiceConfig);
        }
        // 5. 返回bean
        return bean;
    }

    /**
     * 客户端进行rpc调用的时候，让clientProxy帮忙调用而不是真正自己进行通信调用
     *
     * 为标注了@RpcReference注解的字段动态生成代理对象，从而实现远程服务的调用
     *
     * 这里并不需要进行缓存，因为创建的bean实际上已经被Spring容器缓存了。也就是说，
     * 对于单例模式的Bean，这个方法只会待用一次，之后bean就会被Sprin缓存起来了
     *
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        // 1. 获取bean的类
        Class<?> targetClass = bean.getClass();
        // 2. 获取bean的所有字段
        Field[] declaredFields = targetClass.getDeclaredFields();
        // 3. 遍历所有的字段
        for (Field declaredField : declaredFields) {
            // 3.1 判断字段是否带有@RpcReference注解
            RpcReference rpcReference = declaredField.getAnnotation(RpcReference.class);
            // 3.2 如果字段带有@RpcReference注解，则进行处理
            if (rpcReference != null) {
                // 3.3 构建RpcServiceConfig
                RpcServiceConfig rpcServiceConfig = RpcServiceConfig.builder()
                        .group(rpcReference.group())
                        .version(rpcReference.version()).build();
                // 3.4 创建代理对象
                RpcClientProxy rpcClientProxy = new RpcClientProxy(rpcClient, rpcServiceConfig);
                // 3.5 获取代理对象
                Object clientProxy = rpcClientProxy.getProxy(declaredField.getType());
                // 3.6 设置字段可访问
                declaredField.setAccessible(true);
                try {
                    // 3.7 注入代理对象
                    declaredField.set(bean, clientProxy);
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
        // 4. 返回bean
        return bean; 
    }
}
