package io.github.backoutside.rivet.remoting.transport.netty.server;

import io.github.backoutside.rivet.enums.CompressTypeEnum;
import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.enums.SerializationTypeEnum;
import io.github.backoutside.rivet.exception.RpcException;
import io.github.backoutside.rivet.factory.SingletonFactory;
import io.github.backoutside.rivet.metrics.RpcMetrics;
import io.github.backoutside.rivet.remoting.constants.RpcConstants;
import io.github.backoutside.rivet.remoting.dto.RpcMessage;
import io.github.backoutside.rivet.remoting.dto.RpcRequest;
import io.github.backoutside.rivet.remoting.dto.RpcResponse;
import io.github.backoutside.rivet.remoting.handler.RpcRequestHandler;
import io.github.backoutside.rivet.tracing.RpcTracing;
import io.opentelemetry.context.Scope;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

/** Executes a request off the IO event loop and always correlates its reply. */
@Slf4j
@ChannelHandler.Sharable
public class NettyRpcServerHandler extends ChannelInboundHandlerAdapter {
    private final RpcRequestHandler requestHandler = SingletonFactory.getInstance(RpcRequestHandler.class);
    private final RpcAdmissionHandler admission;

    public NettyRpcServerHandler() {
        this(null);
    }

    NettyRpcServerHandler(RpcAdmissionHandler admission) {
        this.admission = admission;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        long started = System.nanoTime();
        RpcRequest measuredRequest = null;
        boolean admitted = false;
        String outcome = "success";
        Scope scope = null;
        RpcAdmissionHandler.TracedRequest traced = msg instanceof RpcAdmissionHandler.TracedRequest
                ? (RpcAdmissionHandler.TracedRequest) msg : null;
        try {
            RpcMessage incoming = traced != null ? traced.message()
                    : msg instanceof RpcMessage ? (RpcMessage) msg : null;
            if (incoming == null) {
                log.warn("Closing RPC channel after an unexpected message type");
                ctx.close();
                return;
            }
            if (incoming.getMessageType() == RpcConstants.HEARTBEAT_REQUEST_TYPE) {
                writeReply(ctx, RpcConstants.HEARTBEAT_RESPONSE_TYPE, RpcConstants.PONG);
                return;
            }
            if (!(incoming.getData() instanceof RpcRequest)) {
                log.warn("Closing RPC channel after an uncorrelatable request message");
                ctx.close();
                return;
            }
            RpcRequest request = (RpcRequest) incoming.getData();
            measuredRequest = request;
            admitted = traced != null;
            if (incoming.getMessageType() == RpcConstants.REQUEST_TYPE) {
                if (traced == null) {
                    // Direct handler usage (without the admission pipeline) still creates a server span.
                    traced = new RpcAdmissionHandler.TracedRequest(incoming,
                            RpcTracing.startServer(request), ctx.channel());
                }
                scope = RpcTracing.scope(traced.span());
            }
            if (request.getRequestId() == null || request.getRequestId().isBlank()) {
                log.warn("Closing RPC channel after a request without a correlation ID");
                if (traced != null) {
                    traced.finish(RpcStatusCode.INVALID_REQUEST.getCode(), null);
                }
                ctx.close();
                return;
            }
            RpcResponse<Object> response;
            Throwable failure = null;
            try {
                if (incoming.getMessageType() != RpcConstants.REQUEST_TYPE) {
                    throw new RpcException(RpcStatusCode.INVALID_REQUEST, request.getRequestId(),
                            "Invalid RPC request message type", null);
                }
                Object result = requestHandler.handle(request);
                response = RpcResponse.success(result, request.getRequestId());
            } catch (RpcException ex) {
                failure = ex;
                outcome = "remote_error";
                RpcStatusCode status = wireFailure(ex);
                log.warn("RPC request {} failed with {}", request.getRequestId(), status, ex);
                response = RpcResponse.fail(status, request.getRequestId());
            } catch (Exception ex) {
                failure = ex;
                outcome = "remote_error";
                log.error("RPC request {} failed unexpectedly", request.getRequestId(), ex);
                response = RpcResponse.fail(RpcStatusCode.INTERNAL_ERROR, request.getRequestId());
            }
            writeReply(ctx, RpcConstants.RESPONSE_TYPE, response, traced, failure);
        } catch (RuntimeException | Error ex) {
            if (traced != null) {
                traced.finish(RpcStatusCode.INTERNAL_ERROR.getCode(), ex);
            }
            ctx.close();
            throw ex;
        } finally {
            if (scope != null) {
                scope.close();
            }
            if (measuredRequest != null) {
                if (measuredRequest.getInterfaceName() != null && measuredRequest.getMethodName() != null) {
                    RpcMetrics.server(measuredRequest.getRpcServiceName(), measuredRequest.getMethodName(),
                            outcome, System.nanoTime() - started);
                }
                if (admitted && admission != null) {
                    traced.releasePermit(admission);
                }
            }
            ReferenceCountUtil.release(msg);
        }
    }

    private RpcStatusCode wireFailure(RpcException exception) {
        RpcStatusCode status = exception.getKnownStatusCode();
        return status != null && status.isWireResponse() && status != RpcStatusCode.SUCCESS
                ? status : RpcStatusCode.INTERNAL_ERROR;
    }

    private void writeReply(ChannelHandlerContext ctx, byte type, Object data) {
        writeReply(ctx, type, data, null, null);
    }

    private void writeReply(ChannelHandlerContext ctx, byte type, Object data,
                            RpcAdmissionHandler.TracedRequest traced, Throwable failure) {
        Integer status = data instanceof RpcResponse<?> ? ((RpcResponse<?>) data).getCode() : null;
        try {
            ctx.writeAndFlush(reply(type, data)).addListener((ChannelFutureListener) write -> {
                if (traced != null) {
                    traced.finishWrite(status, write, failure);
                }
                if (!write.isSuccess()) {
                    log.warn("Could not encode or send RPC response", write.cause());
                    write.channel().close();
                }
            });
        } catch (RuntimeException | Error ex) {
            if (traced != null) {
                traced.finish(RpcAdmissionHandler.writeFailureStatus(ex), ex);
            }
            throw ex;
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
