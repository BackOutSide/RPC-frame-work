package io.github.backoutside.rivet.remoting.transport.netty.client;

import io.github.backoutside.rivet.enums.CompressTypeEnum;
import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.enums.SerializationTypeEnum;
import io.github.backoutside.rivet.exception.RpcException;
import io.github.backoutside.rivet.factory.SingletonFactory;
import io.github.backoutside.rivet.remoting.constants.RpcConstants;
import io.github.backoutside.rivet.remoting.dto.RpcMessage;
import io.github.backoutside.rivet.remoting.dto.RpcResponse;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.CodecException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

/** Handles replies, heartbeats, and failure of in-flight requests. */
@Slf4j
public class NettyRpcClientHandler extends ChannelInboundHandlerAdapter {
    private final UnprocessedRequests requests = SingletonFactory.getInstance(UnprocessedRequests.class);

    @Override
    @SuppressWarnings("unchecked")
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (msg instanceof RpcMessage) {
                RpcMessage message = (RpcMessage) msg;
                if (message.getMessageType() == RpcConstants.RESPONSE_TYPE) {
                    if (!(message.getData() instanceof RpcResponse)
                            || ((RpcResponse<?>) message.getData()).getRequestId() == null) {
                        requests.failChannel(ctx.channel(), RpcStatusCode.INVALID_RESPONSE,
                                "RPC response is missing a valid request ID or body", null);
                        ctx.close();
                        return;
                    }
                    requests.complete((RpcResponse<Object>) message.getData());
                } else if (message.getMessageType() != RpcConstants.HEARTBEAT_RESPONSE_TYPE) {
                    requests.failChannel(ctx.channel(), RpcStatusCode.PROTOCOL_ERROR,
                            "Unexpected RPC message type from provider", null);
                    ctx.close();
                }
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof IdleStateEvent
                && ((IdleStateEvent) event).state() == IdleState.WRITER_IDLE) {
            RpcMessage heartbeat = RpcMessage.builder()
                    .codec(SerializationTypeEnum.HESSIAN.getCode())
                    .compress(CompressTypeEnum.GZIP.getCode())
                    .messageType(RpcConstants.HEARTBEAT_REQUEST_TYPE)
                    .data(RpcConstants.PING).build();
            ctx.writeAndFlush(heartbeat).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        } else {
            super.userEventTriggered(ctx, event);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        requests.failChannel(ctx.channel(), RpcStatusCode.CONNECTION_LOST,
                "RPC connection closed", null);
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("RPC client channel failed: {}", ctx.channel().remoteAddress(), cause);
        RpcStatusCode code = classify(cause);
        requests.failChannel(ctx.channel(), code, "RPC client channel failed", cause);
        ctx.close();
    }

    private static RpcStatusCode classify(Throwable cause) {
        boolean codecFailure = false;
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof RpcException) {
                RpcStatusCode code = ((RpcException) current).getKnownStatusCode();
                if (code == RpcStatusCode.PROTOCOL_ERROR || code == RpcStatusCode.CODEC_ERROR) {
                    return code;
                }
            }
            if (current instanceof TooLongFrameException) {
                return RpcStatusCode.PROTOCOL_ERROR;
            }
            codecFailure |= current instanceof CodecException;
        }
        return codecFailure ? RpcStatusCode.CODEC_ERROR : RpcStatusCode.CONNECTION_LOST;
    }
}
