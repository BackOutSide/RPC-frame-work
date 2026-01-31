import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicStampedReference;


public class NioServer {

    //监听端口
    private static final int PORT = 8080;

    public static void main(String[] args) {

        // 1. 创建Selector
        Selector selector = Selector.open();

        // 2. 配置ServerSocketChannel
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.configureBlocking(false); 
        serverSocketChannel.bind(new InetSocketAddress("localhost", PORT));
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

        // 3. 轮询事件
        while (true) {
            // 3.1 使用select方法阻塞等待事件
            selector.select();

            // 3.2 获取事件集合
            Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();

            //3.3 遍历事件集合
            while (iterator.hasNext()) {
                SelectionKey selectionKey = iterator.next();
                selector.selectedKeys().remove(selectionKey);

                //3.4 处理读事件
                if (selectionKey.isAcceptable()) {
                    ServerSocketChannel serverSocketChannel = (ServerSocketChannel) selectionKey.channel();
                    SocketChannel socketChannel = serverSocketChannel.accept();
                    socketChannel.configureBlocking(false);
                    socketChannel.register(selector, SelectionKey.OP_READ);
                }
            
                //3.5 处理写事件
                if (selectionKey.isReadable()) {
                    SocketChannel socketChannel = (SocketChannel) selectionKey.channel();
                    ByteBuffer buffer = ByteBuffer.allocate(1024);
                    int length  = socketChannel.read(buffer);
                    if (length == -1) {
                        socketChannel.close();
                    }else{
                        buffer.flip();
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        System.out.println(new String(bytes));
                        socketChannel.write(buffer);
                    }
                }
            }
        }
}
