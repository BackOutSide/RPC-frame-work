import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;


public class NioClient {

    private static final String HOST = "localhost";
    private static final int PORT = 8080;

    public static void main(String[] args) {
        Thread Tom = new Thread(()->{
            try {
                sendHello();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        Thread Jerry = new Thread(()->{
            try {
                sendHello();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        Tom.start();
        Jerry.start();
    }

    private static void sendHello() throws IOException {
        SocketChannel socketChannel = SocketChannel.open();
        socketChannel.connect(new InetSocketAddress(HOST, PORT));
        socketChannel.write(ByteBuffer.wrap("Hello, Server".getBytes()));
    }

}
