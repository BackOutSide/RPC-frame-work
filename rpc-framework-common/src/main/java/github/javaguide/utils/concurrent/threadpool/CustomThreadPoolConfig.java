package github.javaguide.utils.concurrent.threadpool;

import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 线程池自定义配置类，可自行根据业务场景修改配置参数。
 *
 */
@Setter
@Getter
public class CustomThreadPoolConfig {
    /**
     * 线程池默认参数
     */
    private static final int DEFAULT_CORE_POOL_SIZE = 10;// 核心线程数
    private static final int DEFAULT_MAXIMUM_POOL_SIZE_SIZE = 100;// 最大线程数
    private static final int DEFAULT_KEEP_ALIVE_TIME = 1;// 存活时间
    private static final TimeUnit DEFAULT_TIME_UNIT = TimeUnit.MINUTES;// 时间单位
    private static final int DEFAULT_BLOCKING_QUEUE_CAPACITY = 100;// 默认队列容量
    private static final int BLOCKING_QUEUE_CAPACITY = 100;// 队列容量
    /**
     * 可配置参数
     */
    private int corePoolSize = DEFAULT_CORE_POOL_SIZE; // 核心线程数
    private int maximumPoolSize = DEFAULT_MAXIMUM_POOL_SIZE_SIZE; // 最大线程数
    private long keepAliveTime = DEFAULT_KEEP_ALIVE_TIME; // 存活时间
    private TimeUnit unit = DEFAULT_TIME_UNIT; // 时间单位
    // 使用有界队列
    private BlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<>(BLOCKING_QUEUE_CAPACITY);
}
