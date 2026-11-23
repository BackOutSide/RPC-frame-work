package io.github.backoutside.rivet.remoting.transport.netty.server;

import io.github.backoutside.rivet.enums.CompressTypeEnum;
import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.enums.SerializationTypeEnum;
import io.github.backoutside.rivet.exception.RpcException;
import io.github.backoutside.rivet.metrics.RpcMetrics;
import io.github.backoutside.rivet.remoting.constants.RpcConstants;
import io.github.backoutside.rivet.remoting.dto.RpcMessage;
import io.github.backoutside.rivet.remoting.dto.RpcRequest;
import io.github.backoutside.rivet.remoting.dto.RpcResponse;
import io.github.backoutside.rivet.tracing.RpcTracing;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.CodecException;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Rejects excess RPC work on the IO event loop before queuing business tasks. */
@Slf4j
@ChannelHandler.Sharable
public final class RpcAdmissionHandler extends ChannelInboundHandlerAdapter {
    private final int limit;
    private final Semaphore permits;

    public RpcAdmissionHandler(int limit) {
        this.limit = limit;
        this.permits = new Semaphore(limit);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof RpcMessage heartbeat
                && heartbeat.getMessageType() == RpcConstants.HEARTBEAT_REQUEST_TYPE) {
            ctx.writeAndFlush(RpcMessage.builder()
                    .codec(SerializationTypeEnum.HESSIAN.getCode())
                    .compress(CompressTypeEnum.GZIP.getCode())
                    .messageType(RpcConstants.HEARTBEAT_RESPONSE_TYPE)
                    .data(RpcConstants.PONG).build())
                    .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            ReferenceCountUtil.release(msg);
            return;
        }
        if (!(msg instanceof RpcMessage message)
                || message.getMessageType() != RpcConstants.REQUEST_TYPE
                || !(message.getData() instanceof RpcRequest request)) {
            ctx.fireChannelRead(msg);
            return;
        }
        TracedRequest traced = new TracedRequest(message, RpcTracing.startServer(request), ctx.channel());
        if (!permits.tryAcquire()) {
            try {
                reject(ctx, request, traced, null);
            } finally {
                ReferenceCountUtil.release(msg);
            }
            return;
        }
        try {
            ctx.fireChannelRead(traced);
        } catch (RejectedExecutionException ex) {
            traced.releasePermit(this);
            try {
                reject(ctx, request, traced, ex);
            } finally {
                ReferenceCountUtil.release(msg);
            }
        } catch (RuntimeException | Error ex) {
            traced.releasePermit(this);
            traced.finish(RpcStatusCode.INTERNAL_ERROR.getCode(), ex);
            ReferenceCountUtil.release(msg);
            ctx.close();
            throw ex;
        }
    }

    void release() {
        permits.release();
    }

    public int inFlight() {
        return limit - permits.availablePermits();
    }

    private void reject(ChannelHandlerContext ctx, RpcRequest request, TracedRequest traced, Throwable failure) {
        try (Scope ignored = RpcTracing.scope(traced.span())) {
            RpcMetrics.serverRejection(request.getRpcServiceName());
            String requestId = request.getRequestId();
            if (requestId == null || requestId.isBlank()) {
                log.warn("Closing RPC channel after an overloaded request without a correlation ID");
                traced.finish(RpcStatusCode.INVALID_REQUEST.getCode(), failure);
                ctx.close();
                return;
            }
            RpcResponse<Object> response = RpcResponse.fail(RpcStatusCode.OVERLOADED,
                    requestId, "RPC provider is overloaded");
            try {
                ctx.writeAndFlush(RpcMessage.builder()
                        .codec(SerializationTypeEnum.HESSIAN.getCode())
                        .compress(CompressTypeEnum.GZIP.getCode())
                        .messageType(RpcConstants.RESPONSE_TYPE)
                        .data(response).build()).addListener((ChannelFutureListener) write -> {
                            traced.finishWrite(response.getCode(), write, failure);
                            if (!write.isSuccess()) {
                                write.channel().close();
                            }
                        });
            } catch (RuntimeException | Error ex) {
                traced.finish(writeFailureStatus(ex), ex);
                throw ex;
            }
        } catch (RuntimeException | Error ex) {
            traced.finish(RpcStatusCode.INTERNAL_ERROR.getCode(), ex);
            ctx.close();
            throw ex;
        }
    }

    /** Carries one decoded request's span across the IO and business executor threads. */
    static final class TracedRequest {
        private final RpcMessage message;
        private final Span span;
        private final ChannelFuture closeFuture;
        private final ChannelFutureListener closeListener;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean permitReleased = new AtomicBoolean();

        TracedRequest(RpcMessage message, Span span, Channel channel) {
            this.message = message;
            this.span = span;
            if (span.isRecording()) {
                this.closeFuture = channel.closeFuture();
                this.closeListener = closed -> finish(RpcStatusCode.CONNECTION_LOST.getCode(), closed.cause());
                closeFuture.addListener(closeListener);
            } else {
                this.closeFuture = null;
                this.closeListener = null;
            }
        }

        RpcMessage message() {
            return message;
        }

        Span span() {
            return span;
        }

        void releasePermit(RpcAdmissionHandler admission) {
            if (permitReleased.compareAndSet(false, true)) {
                admission.release();
            }
        }

        void finishWrite(Integer status, ChannelFuture write, Throwable failure) {
            if (write.isSuccess()) {
                finish(status, failure);
            } else {
                finish(writeFailureStatus(write.cause()), write.cause());
            }
        }

        void finish(Integer status, Throwable failure) {
            if (finished.compareAndSet(false, true)) {
                if (closeFuture != null) {
                    closeFuture.removeListener(closeListener);
                }
                RpcTracing.finish(span, status, failure);
            }
        }
    }

    static int writeFailureStatus(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if ((cause instanceof RpcException rpcException
                    && rpcException.getKnownStatusCode() == RpcStatusCode.CODEC_ERROR)
                    || cause instanceof CodecException) {
                return RpcStatusCode.CODEC_ERROR.getCode();
            }
        }
        return RpcStatusCode.SEND_FAILED.getCode();
    }
}
