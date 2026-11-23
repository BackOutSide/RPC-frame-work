package io.github.backoutside.rivet.tracing;

import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.exception.RpcException;
import io.github.backoutside.rivet.remoting.dto.RpcRequest;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.slf4j.MDC;

import java.util.List;

/** OpenTelemetry instrumentation for the Netty RPC transport. */
public final class RpcTracing {
    private static final String SCOPE_NAME = "io.github.backoutside.rivet.rpc";
    private static final String TRACE_ID = "traceId";
    private static final String TRACEPARENT = "traceparent";
    private static final String TRACESTATE = "tracestate";
    private static final W3CTraceContextPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();
    private static final TextMapGetter<RpcRequest> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(RpcRequest request) {
            return List.of(TRACEPARENT, TRACESTATE);
        }

        @Override
        public String get(RpcRequest request, String key) {
            return switch (key) {
                case TRACEPARENT -> request.getTraceparent();
                case TRACESTATE -> request.getTracestate();
                default -> null;
            };
        }
    };

    private RpcTracing() {
    }

    /** The global RequestID for the active trace, or null without a valid span. */
    public static String currentRequestId() {
        SpanContext context = Span.current().getSpanContext();
        return context.isValid() ? context.getTraceId() : null;
    }

    public static Span startClient(RpcRequest request) {
        return start(request, SpanKind.CLIENT, Context.current());
    }

    public static Span startServer(RpcRequest request) {
        // Each inbound message supplies its own parent. A reused channel or thread may serve many traces.
        Context parent = PROPAGATOR.extract(Context.root(), request, GETTER);
        return start(request, SpanKind.SERVER, parent);
    }

    private static Span start(RpcRequest request, SpanKind kind, Context parent) {
        String method = request.getInterfaceName() + "/" + request.getMethodName();
        return GlobalOpenTelemetry.getTracer(SCOPE_NAME).spanBuilder(method)
                .setSpanKind(kind)
                .setParent(parent)
                .setAttribute("rpc.system.name", "rivet")
                .setAttribute("rpc.method", method)
                .startSpan();
    }

    /** Injects the active logical call into a fresh physical request. */
    public static void inject(RpcRequest.RpcRequestBuilder request) {
        PROPAGATOR.inject(Context.current(), request, (builder, key, value) -> {
            switch (key) {
                case TRACEPARENT -> builder.traceparent(value);
                case TRACESTATE -> builder.tracestate(value);
                default -> { }
            }
        });
    }

    public static Scope scope(Span span) {
        Scope scope = span.makeCurrent();
        String traceId = currentRequestId();
        if (traceId == null) {
            return scope;
        }
        String previous = MDC.get(TRACE_ID);
        MDC.put(TRACE_ID, traceId);
        return () -> {
            try {
                if (previous == null) {
                    MDC.remove(TRACE_ID);
                } else {
                    MDC.put(TRACE_ID, previous);
                }
            } finally {
                scope.close();
            }
        };
    }

    public static void finish(Span span, Integer rpcStatus, Throwable failure) {
        try {
            if (rpcStatus == null && failure instanceof RpcException rpcException) {
                rpcStatus = rpcException.getCode();
            }
            if (rpcStatus != null) {
                span.setAttribute("rpc.status_code", rpcStatus.toString());
            }
            if (failure == null && Integer.valueOf(RpcStatusCode.SUCCESS.getCode()).equals(rpcStatus)) {
                return;
            }
            span.setAttribute("error.type", errorType(rpcStatus, failure));
            span.setStatus(StatusCode.ERROR);
        } finally {
            span.end();
        }
    }

    private static String errorType(Integer rpcStatus, Throwable failure) {
        if (rpcStatus == null || rpcStatus == RpcStatusCode.SUCCESS.getCode()) {
            return failure == null ? RpcStatusCode.INVALID_RESPONSE.name() : failure.getClass().getName();
        }
        RpcStatusCode status = RpcStatusCode.fromCode(rpcStatus);
        return status == null ? rpcStatus.toString() : status.name();
    }
}
