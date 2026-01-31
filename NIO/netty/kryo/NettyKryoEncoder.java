import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自定义编码器
 */
@Slf4j
/

public class NettyKryoEncoder extends MessageToByteEncoder<Object>{

    private final Serializer serializer;
    private final Class<?> genericClass;

    /**
     * 将对象转换为字节码然后写入到ByteBuf中
     */
    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) throws Exception {
        if(genericClass.isInstance(msg)){
            // 将对象转换为byte
            byte[] body = serializer.serialize(msg);
            //2.读取消息的长度
            int dataLength = body.length;
            //3.写入消息对应的字节数组长度，writerIndex的起始位置+4
            byteBuf.writeInt(dataLength);
            //4.将字节数组写入到ByteBuf中
            byteBuf.writeBytes(body);
        }
    }
}
