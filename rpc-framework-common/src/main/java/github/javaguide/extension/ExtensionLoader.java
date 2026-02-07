package github.javaguide.extension;

import github.javaguide.factory.SingletonFactory;
import github.javaguide.utils.StringUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * refer to dubbo spi: https://dubbo.apache.org/zh-cn/docs/source_code_guide/dubbo-spi.html
 */
@Slf4j
public final class ExtensionLoader<T> {

    // 扩展类所在的目录
    private static final String SERVICE_DIRECTORY = "META-INF/extensions/";

    /**
     * 扩展类加载器的缓存，每一个类都有一个扩展类加载器。
     *
     */
    private static final Map<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<>();

    /**
     * 扩展类型接口具体类型
     * 
     */
    private final Class<?> type;

    /**
     * 实例缓存，根据名字进行缓存
     * 例如：
     * netty=github.javaguide.remoting.transport.netty.client.NettyRpcClient
     * socket=github.javaguide.remoting.transport.socket.SocketRpcClient
     * 
     */
    private final Map<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<>();

    /**
     * 类缓存，根据名称进行缓存
     * 例如：netty对应github.javaguide.remoting.transport.netty.client.NettyRpcClient
     * 
     */
    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<>();

    private ExtensionLoader(Class<?> type) {
        this.type = type;
    }

    /**
     * 获取扩展类加载器
     * 例如：获取RpcRequestTransport的扩展类加载器
     * 
     */
    public static <S> ExtensionLoader<S> getExtensionLoader(Class<S> type) {
        // 1. 判断类型是否为空
        if (type == null) {
            throw new IllegalArgumentException("Extension type should not be null.");
        }
        // 2. 判断类型是否为接口
        if (!type.isInterface()) {
            // 需要是接口
            throw new IllegalArgumentException("Extension type must be an interface.");
        }
        // 3. 判断类型是否包含SPI注解
        if (type.getAnnotation(SPI.class) == null) {
            throw new IllegalArgumentException("Extension type must be annotated by @SPI");
        }
        // 4.创建类加载器，直接就是使用ConcurrentHashMap进行创建的，每一个接口类有一个自己的类加载器
        ExtensionLoader<S> extensionLoader = (ExtensionLoader<S>) EXTENSION_LOADERS.get(type);
        if (extensionLoader == null) {
            // 4.1 如果缓存中没有，则创建一个新的类加载器
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<S>(type));
            // 4.2 从缓存中获取类加载器
            extensionLoader = (ExtensionLoader<S>) EXTENSION_LOADERS.get(type);
        }
        return extensionLoader;
    }

    /**
     * 获取扩展类实例
     * @param name 扩展类名称
     * @return 扩展类
     */
    public T getExtension(String name) {
        // 1. 判断名称是否为空
        if (StringUtil.isBlank(name)) {
            throw new IllegalArgumentException("Extension name should not be null or empty.");
        }
        // 2. 创建一个holder容器对象，如果没有的情况下，创建一个新的holder容器对象
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            // 2.1 如果缓存中没有，则创建一个新的对象
            cachedInstances.putIfAbsent(name, new Holder<>());
            // 2.2 从缓存中获取对象
            holder = cachedInstances.get(name);
        }
        // 3. 单例模式创建对象，双检测锁。没有只是使用ConcurrentHashMap
        Object instance = holder.get();
        if (instance == null) {
            // 3.1 如果对象为空，则创建一个新的对象
            synchronized (holder) {
                // 3.2 从缓存中获取对象
                instance = holder.get();
                if (instance == null) {
                    // 3.3 创建一个新的对象
                    instance = createExtension(name);
                    // 3.4 将对象放入到缓存中
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * 使用SigletonFactory创建单例bean
     * 
     */
    private T createExtension(String name) {
        // 1. 首先获取扩展类加载器
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null) {
            throw new RuntimeException("扩展类不存在:  " + name);
        }
        // 2. 获取实例
        return (T) SingletonFactory.getInstance(clazz);
    }

    /**
     * 获取扩展类
     * @return 扩展类
     */
    private Map<String, Class<?>> getExtensionClasses() {
        // 1. 从缓存中获取所有的类
        Map<String, Class<?>> classes = cachedClasses.get();
        // 2. 缓存中没有，进行双检测锁
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                    classes = new HashMap<>();
                    // 3. 从文件夹中加载所有的扩展类
                    loadDirectory(classes);
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    /**
     * java的SPI机制：加载扩展类
     * @param extensionClasses 扩展类
     * */
    private void loadDirectory(Map<String, Class<?>> extensionClasses) {
        // 1. 构建配置文件的路径
        String fileName = ExtensionLoader.SERVICE_DIRECTORY + type.getName();
        // 2. 加载扩展类
        try {
            Enumeration<URL> urls;
            // 2.1 Java的SPI，扩展类加载器，然后设置文件的URl
            ClassLoader classLoader = ExtensionLoader.class.getClassLoader();
            urls = classLoader.getResources(fileName);
            // 2.2 如果urls不为空，则遍历urls
            if (urls != null) {
                // 2.2.1 遍历urls
                while (urls.hasMoreElements()) {
                    // 2.2.1.1 获取下一个URL
                    URL resourceUrl = urls.nextElement();
                    // 2.2.1.2 加载并解析
                    loadResource(extensionClasses, classLoader, resourceUrl);
                }
            }
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

    /**
     * 加载扩展类资源文件
     * @param extensionClasses 扩展类
     * @param classLoader 类加载器
     * @param resourceUrl 资源URL
     * */
    private void loadResource(Map<String, Class<?>> extensionClasses, ClassLoader classLoader, URL resourceUrl) {
        // 1. 读取资源文件，使用BufferedReader读取资源文件
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resourceUrl.openStream(), UTF_8))) {
            String line;
            // 1.1 读取配置文件的没一行
            while ((line = reader.readLine()) != null) {
                // 1.1.1 过滤掉注释
                final int ci = line.indexOf('#');
                if (ci >= 0) {
                    line = line.substring(0, ci);
                }
                // 1.1.2 去掉空格
                line = line.trim();
                if (line.length() > 0) {
                    try {
                        // 1.1.3 = 实现的key value对解析，存入到map中
                        final int ei = line.indexOf('=');
                        String name = line.substring(0, ei).trim();
                        String clazzName = line.substring(ei + 1).trim();
                        if (name.length() > 0 && clazzName.length() > 0) {
                            // 1.1.4 Java的SPI的具体实现
                            Class<?> clazz = classLoader.loadClass(clazzName);
                            extensionClasses.put(name, clazz);
                        }
                    } catch (ClassNotFoundException e) {
                        log.error(e.getMessage());
                    }
                }

            }
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }
}
