package io.github.backoutside.rivet.remoting.transport.netty.codec;

import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.exception.RpcException;
import io.github.backoutside.rivet.remoting.constants.RpcConstants;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 * @ClassName RpcMessageFrameDecoder
 * @Description RPCMessageFrame解析器 不能共享
 * @Author jiangyang1556
 * @Date 2025/9/28 9:36
 * @Version 1.0
 **/
public class RpcMessageFrameDecoder extends LengthFieldBasedFrameDecoder {
    public RpcMessageFrameDecoder() {
        super(RpcConstants.MAX_FRAME_LENGTH, 5, 4, -9, 0);
    }

    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        try {
            return super.decode(ctx, in);
        } catch (DecoderException cause) {
            throw new RpcException(RpcStatusCode.PROTOCOL_ERROR, null, "Invalid RPC frame length", cause);
        }
    }
}
