package io.github.backoutside.rivet.remoting.transport.netty.server;

import io.github.backoutside.rivet.enums.CompressTypeEnum;
import io.github.backoutside.rivet.enums.RpcResponseCodeEnum;
import io.github.backoutside.rivet.enums.SerializationTypeEnum;
import io.github.backoutside.rivet.factory.SingletonFactory;
import io.github.backoutside.rivet.remoting.constants.RpcConstants;
import io.github.backoutside.rivet.remoting.dto.RpcMessage;
import io.github.backoutside.rivet.remoting.dto.RpcRequest;
import io.github.backoutside.rivet.remoting.dto.RpcResponse;
import io.github.backoutside.rivet.remoting.handler.RpcRequestHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

/** Executes a request off the IO event loop and always correlates its reply. */
@Slf4j
public class NettyRpcServerHandler extends ChannelInboundHandlerAdapter {
    private final RpcRequestHandler requestHandler = SingletonFactory.getInstance(RpcRequestHandler.class);

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (!(msg instanceof RpcMessage)) {
                return;
            }
            RpcMessage incoming = (RpcMessage) msg;
            if (incoming.getMessageType() == RpcConstants.HEARTBEAT_REQUEST_TYPE) {
                ctx.writeAndFlush(reply(RpcConstants.HEARTBEAT_RESPONSE_TYPE, RpcConstants.PONG))
                        .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                return;
            }
            if (incoming.getMessageType() != RpcConstants.REQUEST_TYPE || !(incoming.getData() instanceof RpcRequest)) {
                throw new IllegalArgumentException("Invalid RPC request message");
            }
            RpcRequest request = (RpcRequest) incoming.getData();
            RpcResponse<Object> response;
            try {
                Object result = requestHandler.handle(request);
                response = RpcResponse.success(result, request.getRequestId());
            } catch (Exception ex) {
                log.warn("RPC method {}.{} failed", request.getInterfaceName(), request.getMethodName(), ex);
                response = RpcResponse.fail(RpcResponseCodeEnum.FAIL, request.getRequestId(), ex.getMessage());
            }
            ctx.writeAndFlush(reply(RpcConstants.RESPONSE_TYPE, response))
                    .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private RpcMessage reply(byte type, Object data) {
        return RpcMessage.builder()
                .codec(SerializationTypeEnum.HESSIAN.getCode())
                .compress(CompressTypeEnum.GZIP.getCode())
                .messageType(type).data(data).build();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof IdleStateEvent
                && ((IdleStateEvent) event).state() == IdleState.READER_IDLE) {
            ctx.close();
        } else {
            super.userEventTriggered(ctx, event);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("RPC server channel failed", cause);
        ctx.close();
    }
}
