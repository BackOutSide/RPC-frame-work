package io.github.backoutside.rivet.remoting.transport.netty.server;

import io.github.backoutside.rivet.config.RpcServiceConfig;
import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.exception.RpcException;
import io.github.backoutside.rivet.provider.impl.ZkServiceProviderImpl;
import io.github.backoutside.rivet.remoting.constants.RpcConstants;
import io.github.backoutside.rivet.remoting.dto.RpcMessage;
import io.github.backoutside.rivet.remoting.dto.RpcRequest;
import io.github.backoutside.rivet.remoting.dto.RpcResponse;
import io.github.backoutside.rivet.remoting.handler.RpcRequestHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class NettyRpcServerHandlerTest {
    @BeforeAll
    static void registerService() {
        ZkServiceProviderImpl.getInstance().addService(RpcServiceConfig.builder()
                .service(new TestServiceImpl()).interfaceClass(TestService.class)
                .group("handler-test").version("v1").build());
    }

    @Test
    void nullReturnStillProducesSuccessfulCorrelatedResponse() {
        RpcResponse<?> response = invoke("nullable", "null-request");
        assertEquals("null-request", response.getRequestId());
        assertEquals(RpcStatusCode.SUCCESS.getCode(), response.getCode());
        assertNull(response.getData());
    }

    @Test
    void providerExceptionProducesCorrelatedFailureWithoutLeakingMessage() {
        RpcResponse<?> response = invoke("fail", "failed-request");
        assertEquals("failed-request", response.getRequestId());
        assertEquals(RpcStatusCode.PROVIDER_EXCEPTION.getCode(), response.getCode());
        assertEquals(RpcStatusCode.PROVIDER_EXCEPTION.getMessage(), response.getMessage());
    }

    @Test
    void missingServiceAndMethodHaveDistinctStatusCodes() {
        RpcRequest missingService = request("nullable", "missing-service", "other-group");
        assertEquals(RpcStatusCode.SERVICE_NOT_FOUND.getCode(), invoke(missingService).getCode());
        assertEquals(RpcStatusCode.METHOD_NOT_FOUND.getCode(), invoke("absent", "missing-method").getCode());
    }

    @Test
    void malformedCorrelatableRequestReceivesInvalidRequest() {
        RpcRequest invalid = RpcRequest.builder()
                .requestId("bad-request").interfaceName(TestService.class.getName())
                .methodName("nullable").group("handler-test").version("v1")
                .parameters(new Object[0]).build();
        RpcResponse<?> response = invoke(invalid);
        assertEquals("bad-request", response.getRequestId());
        assertEquals(RpcStatusCode.INVALID_REQUEST.getCode(), response.getCode());
    }

    @Test
    void reflectionArgumentMismatchReceivesInvalidRequest() {
        RpcRequest invalid = RpcRequest.builder()
                .requestId("bad-argument").interfaceName(TestService.class.getName())
                .methodName("echo").group("handler-test").version("v1")
                .paramTypes(new Class<?>[]{String.class}).parameters(new Object[]{42}).build();
        RpcResponse<?> response = invoke(invalid);
        assertEquals("bad-argument", response.getRequestId());
        assertEquals(RpcStatusCode.INVALID_REQUEST.getCode(), response.getCode());
    }

    @Test
    void requestWithoutCorrelationIdClosesChannelWithoutResponse() {
        EmbeddedChannel channel = new EmbeddedChannel(new NettyRpcServerHandler());
        try {
            channel.writeInbound(RpcMessage.builder()
                    .messageType(RpcConstants.REQUEST_TYPE)
                    .data(request("nullable", null, "handler-test")).build());
            assertNull(channel.readOutbound());
            assertFalse(channel.isOpen());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void admissionPermitIsReleasedOnceForRequestsOnly() {
        RpcAdmissionHandler admission = new RpcAdmissionHandler(1);
        EmbeddedChannel channel = new EmbeddedChannel(admission, new NettyRpcServerHandler(admission));
        try {
            channel.writeInbound(RpcMessage.builder()
                    .messageType(RpcConstants.HEARTBEAT_REQUEST_TYPE).build());
            assertEquals(0, admission.inFlight());
            channel.readOutbound();

            channel.writeInbound(RpcMessage.builder()
                    .messageType(RpcConstants.REQUEST_TYPE)
                    .data(request("nullable", "admitted-request", "handler-test")).build());
            assertEquals(0, admission.inFlight());
            RpcMessage response = channel.readOutbound();
            assertEquals(RpcStatusCode.SUCCESS.getCode(),
                    ((RpcResponse<?>) response.getData()).getCode());

            channel.writeInbound(RpcMessage.builder()
                    .messageType(RpcConstants.REQUEST_TYPE)
                    .data(request("nullable", null, "handler-test")).build());
            assertEquals(0, admission.inFlight());
            assertFalse(channel.isOpen());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void providerExceptionRetainsTargetTypeAsCauseForServerDiagnostics() {
        RpcException error = assertThrows(RpcException.class,
                () -> new RpcRequestHandler().handle(request("fail", "direct-failure", "handler-test")));
        assertEquals(RpcStatusCode.PROVIDER_EXCEPTION.getCode(), error.getCode());
        assertInstanceOf(IllegalArgumentException.class, error.getCause());
        assertEquals("boom", error.getCause().getMessage());
    }

    private RpcResponse<?> invoke(String method, String requestId) {
        return invoke(request(method, requestId, "handler-test"));
    }

    private RpcResponse<?> invoke(RpcRequest request) {
        EmbeddedChannel channel = new EmbeddedChannel(new NettyRpcServerHandler());
        channel.writeInbound(RpcMessage.builder()
                .messageType(RpcConstants.REQUEST_TYPE).data(request).build());
        RpcMessage outbound = channel.readOutbound();
        channel.finishAndReleaseAll();
        return (RpcResponse<?>) outbound.getData();
    }

    private RpcRequest request(String method, String requestId, String group) {
        return RpcRequest.builder()
                .interfaceName(TestService.class.getName()).methodName(method)
                .paramTypes(new Class<?>[0]).parameters(new Object[0])
                .group(group).version("v1").requestId(requestId).build();
    }

    public interface TestService {
        String nullable();
        String fail();
        String echo(String value);
    }

    public static class TestServiceImpl implements TestService {
        @Override public String nullable() { return null; }
        @Override public String fail() { throw new IllegalArgumentException("boom"); }
        @Override public String echo(String value) { return value; }
    }
}
