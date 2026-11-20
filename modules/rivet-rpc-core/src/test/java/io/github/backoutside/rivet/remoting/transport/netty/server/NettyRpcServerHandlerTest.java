package io.github.backoutside.rivet.remoting.transport.netty.server;

import io.github.backoutside.rivet.config.RpcServiceConfig;
import io.github.backoutside.rivet.enums.RpcResponseCodeEnum;
import io.github.backoutside.rivet.provider.impl.ZkServiceProviderImpl;
import io.github.backoutside.rivet.remoting.constants.RpcConstants;
import io.github.backoutside.rivet.remoting.dto.RpcMessage;
import io.github.backoutside.rivet.remoting.dto.RpcRequest;
import io.github.backoutside.rivet.remoting.dto.RpcResponse;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        assertEquals(RpcResponseCodeEnum.SUCCESS.getCode(), response.getCode());
        assertNull(response.getData());
    }

    @Test
    void exceptionProducesCorrelatedFailureResponse() {
        RpcResponse<?> response = invoke("fail", "failed-request");
        assertEquals("failed-request", response.getRequestId());
        assertEquals(RpcResponseCodeEnum.FAIL.getCode(), response.getCode());
        assertEquals("boom", response.getMessage());
    }

    private RpcResponse<?> invoke(String method, String requestId) {
        EmbeddedChannel channel = new EmbeddedChannel(new NettyRpcServerHandler());
        RpcRequest request = RpcRequest.builder()
                .interfaceName(TestService.class.getName()).methodName(method)
                .paramTypes(new Class<?>[0]).parameters(new Object[0])
                .group("handler-test").version("v1").requestId(requestId).build();
        channel.writeInbound(RpcMessage.builder()
                .messageType(RpcConstants.REQUEST_TYPE).data(request).build());
        RpcMessage outbound = channel.readOutbound();
        channel.finishAndReleaseAll();
        return (RpcResponse<?>) outbound.getData();
    }

    public interface TestService {
        String nullable();
        String fail();
    }

    public static class TestServiceImpl implements TestService {
        @Override public String nullable() { return null; }
        @Override public String fail() { throw new IllegalArgumentException("boom"); }
    }
}
