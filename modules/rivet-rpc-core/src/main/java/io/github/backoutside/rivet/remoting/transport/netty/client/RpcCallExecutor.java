package io.github.backoutside.rivet.remoting.transport.netty.client;

import io.github.backoutside.rivet.config.RpcConfiguration;
import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.exception.RpcException;
import io.github.backoutside.rivet.exception.RpcRejectedException;
import io.github.backoutside.rivet.exception.RpcTimeoutException;
import io.github.backoutside.rivet.exception.RpcUnavailableException;
import io.github.backoutside.rivet.metrics.RpcMetrics;
import io.github.backoutside.rivet.registry.ServiceDiscovery;
import io.github.backoutside.rivet.remoting.dto.RpcRequest;
import io.github.backoutside.rivet.remoting.dto.RpcResponse;
import io.github.backoutside.rivet.tracing.RpcTracing;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** A logical call has one deadline, one bulkhead permit and bounded physical attempts. */
final class RpcCallExecutor {
    // ZooKeeper operations may ignore caller cancellation; bound their workers and queue.
    private static final AtomicInteger DISCOVERY_THREAD_ID = new AtomicInteger();
    private static final ThreadPoolExecutor DISCOVERY_EXECUTOR = new ThreadPoolExecutor(
            2, 8, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256), runnable -> {
                Thread thread = new Thread(runnable, "rpc-discovery-" + DISCOVERY_THREAD_ID.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    @FunctionalInterface
    interface Attempt {
        /** The deadline is an absolute value on the {@link System#nanoTime()} clock. */
        RpcResponse<Object> send(RpcRequest request, InetSocketAddress address, long deadlineNanos);
    }

    private final RpcConfiguration config;
    private final ServiceDiscovery discovery;
    private final Attempt attempt;
    private final ConcurrentMap<String, Bulkhead> bulkheads = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    RpcCallExecutor(RpcConfiguration config, ServiceDiscovery discovery, Attempt attempt) {
        this.config = config;
        this.discovery = discovery;
        this.attempt = attempt;
    }

    RpcResponse<Object> execute(RpcRequest request) {
        String service = request.getRpcServiceName();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.getRequestTimeoutMs());
        Bulkhead bulkhead = bulkheads.computeIfAbsent(service, ignored -> Bulkhead.of(service,
                BulkheadConfig.custom().maxConcurrentCalls(config.getMaxConcurrentCalls())
                        .maxWaitDuration(Duration.ZERO).build()));
        if (!bulkhead.tryAcquirePermission()) {
            RpcMetrics.rejection(service, "bulkhead");
            throw new RpcRejectedException(RpcStatusCode.CALL_REJECTED.getCode(), request.getRequestId(),
                    "RPC client concurrent-call limit reached for " + service);
        }
        try {
            List<InetSocketAddress> discovered = discover(request, deadline);
            if (discovered == null) {
                throw new RpcUnavailableException(RpcStatusCode.DISCOVERY_FAILED, request.getRequestId(),
                        "RPC service discovery returned no candidate list for " + service, null);
            }
            if (discovered.isEmpty()) {
                throw new RpcUnavailableException(RpcStatusCode.NO_PROVIDER, request.getRequestId(),
                        "No RPC provider for " + service, null);
            }
            // A service discovery implementation may repeat an address. A logical call
            // must never use the same provider twice or reserve time for a duplicate.
            List<InetSocketAddress> candidates = new ArrayList<>(new LinkedHashSet<>(discovered));
            int allowedAttempts = request.isIdempotent()
                    ? Math.min(config.getMaxAttempts(), candidates.size()) : 1;
            AtomicInteger attempted = new AtomicInteger();
            RpcException lastFailure = null;
            for (int candidateIndex = 0; candidateIndex < candidates.size(); candidateIndex++) {
                InetSocketAddress address = candidates.get(candidateIndex);
                if (attempted.get() >= allowedAttempts) {
                    break;
                }
                if (deadline - System.nanoTime() <= 0) {
                    throw new RpcTimeoutException(RpcStatusCode.RESPONSE_TIMEOUT, request.getRequestId(),
                            "RPC deadline exceeded for " + service, lastFailure);
                }
                if (address == null) {
                    throw new RpcUnavailableException(RpcStatusCode.DISCOVERY_FAILED, request.getRequestId(),
                            "RPC service discovery returned a null provider for " + service, null);
                }
                String breakerName = breakerName(service, address);
                CircuitBreaker breaker = breakers.computeIfAbsent(breakerName, ignored -> newBreaker(breakerName));
                RpcRequest physical = copyWithNewId(request);
                int position = candidateIndex;
                int attemptsBefore = attempted.get();
                try {
                    RpcResponse<Object> reply = breaker.executeSupplier(() -> {
                        long attemptStarted = System.nanoTime();
                        long remaining = deadline - attemptStarted;
                        if (remaining <= 0) {
                            throw new RpcTimeoutException(RpcStatusCode.RESPONSE_TIMEOUT, request.getRequestId(),
                                    "RPC deadline exceeded before sending to " + address, null);
                        }
                        long budget = attemptBudgetNanos(request, service, candidates, position,
                                allowedAttempts - attempted.get(), remaining);
                        if (budget <= 0) {
                            throw new RpcTimeoutException(RpcStatusCode.RESPONSE_TIMEOUT, request.getRequestId(),
                                    "RPC deadline has no time left for an attempt to " + address, null);
                        }
                        long attemptDeadline = attemptStarted + budget;
                        if (attemptDeadline - System.nanoTime() <= 0) {
                            throw new RpcTimeoutException(RpcStatusCode.RESPONSE_TIMEOUT, request.getRequestId(),
                                    "RPC attempt deadline exceeded before sending to " + address, null);
                        }
                        int number = attempted.incrementAndGet();
                        if (number > 1) {
                            RpcMetrics.retry(service);
                            Span.current().addEvent("rivet.rpc.retry",
                                    Attributes.builder().put("rivet.rpc.attempt", number).build());
                        }
                        if (attemptDeadline - System.nanoTime() <= 0) {
                            throw new RpcTimeoutException(RpcStatusCode.RESPONSE_TIMEOUT, request.getRequestId(),
                                    "RPC attempt deadline exceeded before sending to " + address, null);
                        }
                        RpcResponse<Object> result = attempt.send(physical, address, attemptDeadline);
                        if (result == null) {
                            throw new RpcException(RpcStatusCode.INVALID_RESPONSE, request.getRequestId(),
                                    "Empty RPC response from " + address, null);
                        }
                        if (!physical.getRequestId().equals(result.getRequestId())) {
                            throw new RpcException(RpcStatusCode.INVALID_RESPONSE, request.getRequestId(),
                                    "RPC response ID does not match request from " + address, null);
                        }
                        if (attemptDeadline - System.nanoTime() <= 0) {
                            throw new RpcTimeoutException(RpcStatusCode.RESPONSE_TIMEOUT, request.getRequestId(),
                                    "RPC attempt deadline exceeded for " + service, null);
                        }
                        return result;
                    });
                    if (Integer.valueOf(RpcStatusCode.OVERLOADED.getCode()).equals(reply.getCode())) {
                        throw new RpcRejectedException(reply.getCode(), request.getRequestId(),
                                "RPC provider rejected the call: " + address);
                    }
                    RpcMetrics.attempt(service, RpcMetrics.outcome(reply));
                    // The physical ID is private to this attempt. Return the logical ID to the proxy.
                    reply.setRequestId(request.getRequestId());
                    return reply;
                } catch (CallNotPermittedException ex) {
                    RpcMetrics.rejection(service, "circuit");
                } catch (RpcTimeoutException | RpcUnavailableException | RpcRejectedException ex) {
                    if (attempted.get() > attemptsBefore) {
                        RpcMetrics.attempt(service, RpcMetrics.outcome(ex));
                    }
                    lastFailure = ex;
                    if (!request.isIdempotent()
                            || ex.getKnownStatusCode() == RpcStatusCode.CALL_INTERRUPTED) {
                        throw forLogicalCall(ex, request.getRequestId());
                    }
                } catch (RpcException ex) {
                    if (attempted.get() > attemptsBefore) {
                        RpcMetrics.attempt(service, RpcMetrics.outcome(ex));
                    }
                    throw forLogicalCall(ex, request.getRequestId());
                } catch (RuntimeException ex) {
                    if (attempted.get() > attemptsBefore) {
                        RpcMetrics.attempt(service, RpcMetrics.outcome(ex));
                    }
                    throw ex;
                }
            }
            if (lastFailure != null) {
                throw forLogicalCall(lastFailure, request.getRequestId());
            }
            throw new RpcRejectedException(RpcStatusCode.CALL_REJECTED.getCode(), request.getRequestId(),
                    "All RPC provider circuits are open for " + service);
        } finally {
            bulkhead.onComplete();
        }
    }

    private long attemptBudgetNanos(RpcRequest request, String service,
                                    List<InetSocketAddress> candidates, int currentIndex,
                                    int remainingAttemptSlots, long remainingNanos) {
        if (!request.isIdempotent() || config.getPerAttemptTimeoutMs() == 0 || remainingAttemptSlots <= 1) {
            return remainingNanos;
        }
        int possibleAttempts = 1;
        for (int index = currentIndex + 1; index < candidates.size()
                && possibleAttempts < remainingAttemptSlots; index++) {
            InetSocketAddress next = candidates.get(index);
            if (next == null) {
                continue;
            }
            CircuitBreaker known = breakers.get(breakerName(service, next));
            if (known != null) {
                CircuitBreaker.State state = known.getState();
                // This only sizes the current attempt. executeSupplier still decides
                // whether a later circuit actually permits a call.
                if (state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN) {
                    continue;
                }
            }
            possibleAttempts++;
        }
        if (possibleAttempts == 1) {
            return remainingNanos;
        }
        return Math.min(TimeUnit.MILLISECONDS.toNanos(config.getPerAttemptTimeoutMs()),
                remainingNanos / possibleAttempts);
    }

    private static String breakerName(String service, InetSocketAddress address) {
        return service + "@" + address.getHostString() + ":" + address.getPort();
    }

    private CircuitBreaker newBreaker(String name) {
        CircuitBreakerConfig breakerConfig = CircuitBreakerConfig.custom()
                .slidingWindow(config.getCircuitWindowSize(), config.getCircuitMinimumCalls(),
                        CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .failureRateThreshold(50.0f)
                .permittedNumberOfCallsInHalfOpenState(2)
                .waitDurationInOpenState(Duration.ofMillis(config.getCircuitOpenMs()))
                .recordExceptions(RpcTimeoutException.class, RpcUnavailableException.class)
                .recordResult(value -> value instanceof RpcResponse<?> response
                        && (Integer.valueOf(RpcStatusCode.OVERLOADED.getCode()).equals(response.getCode())
                        || Integer.valueOf(RpcStatusCode.INTERNAL_ERROR.getCode()).equals(response.getCode())))
                .build();
        return CircuitBreaker.of(name, breakerConfig);
    }

    private List<InetSocketAddress> discover(RpcRequest request, long deadline) {
        String service = request.getRpcServiceName();
        Future<List<InetSocketAddress>> future;
        try {
            future = DISCOVERY_EXECUTOR.submit(() -> discovery.lookupServices(request));
        } catch (RejectedExecutionException ex) {
            RpcMetrics.rejection(service, "discovery_queue");
            throw new RpcRejectedException(RpcStatusCode.CALL_REJECTED.getCode(), request.getRequestId(),
                    "RPC discovery workers are saturated for " + service);
        }
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new RpcTimeoutException(RpcStatusCode.RESPONSE_TIMEOUT, request.getRequestId(),
                        "RPC deadline exceeded during discovery for " + service, null);
            }
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException ex) {
            throw new RpcTimeoutException(RpcStatusCode.RESPONSE_TIMEOUT, request.getRequestId(),
                    "RPC discovery timed out for " + service, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RpcException(RpcStatusCode.CALL_INTERRUPTED, request.getRequestId(),
                    "Interrupted during RPC discovery for " + service, ex);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RpcException rpcException) {
                RpcStatusCode code = rpcException.getKnownStatusCode() == RpcStatusCode.NO_PROVIDER
                        ? RpcStatusCode.NO_PROVIDER : RpcStatusCode.DISCOVERY_FAILED;
                throw new RpcUnavailableException(code, request.getRequestId(),
                        "RPC service discovery failed for " + service, rpcException);
            }
            throw new RpcUnavailableException(RpcStatusCode.DISCOVERY_FAILED, request.getRequestId(),
                    "RPC service discovery failed for " + service, ex.getCause());
        } finally {
            future.cancel(true);
        }
    }

    private static RpcException forLogicalCall(RpcException failure, String logicalRequestId) {
        if (Objects.equals(logicalRequestId, failure.getRequestId())) {
            return failure;
        }
        RpcStatusCode code = failure.getKnownStatusCode();
        if (code == null) {
            if (failure.getCode() != null) {
                // Unknown remote integers are carried by RpcResponse and interpreted by the proxy.
                return failure;
            }
            // Legacy local exceptions have no code; retain their broad failure class.
            code = failure instanceof RpcTimeoutException
                    ? RpcStatusCode.RESPONSE_TIMEOUT : failure instanceof RpcUnavailableException
                    ? RpcStatusCode.CONNECTION_LOST : RpcStatusCode.INTERNAL_ERROR;
        }
        if (failure instanceof RpcTimeoutException) {
            return new RpcTimeoutException(code, logicalRequestId, failure.getMessage(), failure);
        }
        if (failure instanceof RpcUnavailableException) {
            return new RpcUnavailableException(code, logicalRequestId, failure.getMessage(), failure);
        }
        if (failure instanceof RpcRejectedException) {
            return new RpcRejectedException(code.getCode(), logicalRequestId, failure.getMessage(), failure);
        }
        return new RpcException(code, logicalRequestId, failure.getMessage(), failure);
    }

    private static RpcRequest copyWithNewId(RpcRequest original) {
        RpcRequest.RpcRequestBuilder attempt = RpcRequest.builder()
                .requestId(UUID.randomUUID().toString())
                .interfaceName(original.getInterfaceName())
                .methodName(original.getMethodName())
                .parameters(original.getParameters())
                .paramTypes(original.getParamTypes())
                .group(original.getGroup())
                .version(original.getVersion())
                .idempotent(original.isIdempotent());
        RpcTracing.inject(attempt);
        return attempt.build();
    }
}
