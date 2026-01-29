package socket;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;


/**
 * 服务端
 * 
 */

public class HelloServer {

    /**
     * 创建线程池
     * 核心线程数：10 --始终保持活跃的线程数
     * 最大线程数：50 --线程池中最多可以创建的线程数
     * 空闲线程活跃的时间：60秒
     * 任务队列容量:100
     */
    int corePoolSize = 10;
    int maximumPoolSize = 50;
    long keepAliveTime = 60;
    int  queueCapacity = 100;

    threadPool = new ThreadPoolExecutor(
        corePoolSize, 
        maximumPoolSize, 
        keepAliveTime, 
        TimeUnit.SECONDS, 
        new ArrayBlockingQueue<>(queueCapacity),
        new ThreadPoolExecutor.CallerRunsPolicy()//拒绝策略：当线程池达到最大线程数时，新任务被拒绝时，调用者线程会自己执行该任务
    );

    /**
     * 启动服务器
     * @param port 端口号
     */
    public void start(int port) {
        //创建 ServerSocket 对象并且绑定一个端口
        try (ServerSocket server = new ServerSocket(port);) {
            logger.info("server started on port: " + port);
            Socket socket;
            //主线程只负责接受连接请求，不处理任何逻辑，直接将连接扔给线程池
            while ((socket = server.accept()) != null) {
               logger.info("client connected{}", socket.getInetAddress().getHostAddress());
               final Socket clientSocket = socket;
               //将客户端请求提交到线程池异步处理
               threadPool.execute(new clientHandler(clientSocket));
            }
        } catch (IOException e) {
            logger.error("occur IOException:", e);
        }finally{
            threadPool.shutdown();
            logger.info("server stopped");
        }
    }

    /**
    * 客户端请求处理任务
    */
    private class ClientHandler implements Runnable {
        private final Socket socket;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (ObjectInputStream objectInputStream = new ObjectInputStream(socket.getInputStream());
                ObjectOutputStream objectOutputStream = new ObjectOutputStream(socket.getOutputStream())) {
                // 读取客户端请求
                Message message = (Message) objectInputStream.readObject();
                logger.info("server receive message: {} from thread: {}",
                    message.getContent(), Thread.currentThread().getName());
                
                message.setContent("new content");
                
                // 发送响应
                objectOutputStream.writeObject(message);
                objectOutputStream.flush();
            } catch (IOException | ClassNotFoundException e) {
                logger.error("occur exception:", e);
            } finally {
                // 确保 socket 被关闭
                try {
                    socket.close();
                } catch (IOException e) {
                    logger.error("close socket error:", e);
                }
            }
        }
    }

    /**
     * 优雅关闭线程池
     */
    public void shutdown(){
        if(threadPool != null && !threadPool.isShutdown()){
            threadPool.shutdown();//不再接受新任务
            logger.info("server stopped");
        }
        try{
            //等待已提交的任务完成
            if(!threadPool.awaitTermination(60, TimeUnit.SECONDS)){
                threadPool.shutdownNow();//强制关闭
            }
        }catch(InterruptedException e){
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("thread pool closed");
    }


    public static void main(String[] args) {
        HelloServer helloServer = new HelloServer();
        helloServer.start(6666);
    }
}