package io.github.backoutside.rivet.remoting.transport.netty.server;

import io.github.backoutside.rivet.config.RpcServiceConfig;
import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.provider.impl.ZkServiceProviderImpl;
import io.github.backoutside.rivet.remoting.constants.RpcConstants;
import io.github.backoutside.rivet.remoting.dto.RpcMessage;
import io.github.backoutside.rivet.remoting.dto.RpcRequest;
import io.github.backoutside.rivet.remoting.dto.RpcResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RpcAdmissionHandlerTest {
    @BeforeAll
    static void registerService() {
        ZkServiceProviderImpl.getInstance().addService(RpcServiceConfig.builder()
                .service(new TestServiceImpl()).interfaceClass(TestService.class)
                .group("admission-test").version("v1").build());
    }

    @Test
    void overloadIsRejectedBeforeBusinessHandlerWithOriginalCorrelationId() {
        RpcAdmissionHandler admission = new RpcAdmissionHandler(1);
        List<Object> held = new ArrayList<>();
        EmbeddedChannel channel = new EmbeddedChannel(admission, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                held.add(msg); // Keep the first request in flight until the test releases it.
            }
        });
        try {
            channel.writeInbound(message("first", "ok"));
            assertEquals(1, admission.inFlight());
            assertEquals(1, held.size());

            channel.writeInbound(message("second", "ok"));
            assertEquals(1, held.size(), "rejected request must not reach the business handler");
            RpcMessage rejectedMessage = channel.readOutbound();
            RpcResponse<?> rejected = (RpcResponse<?>) rejectedMessage.getData();
            assertEquals(RpcConstants.RESPONSE_TYPE, rejectedMessage.getMessageType());
            assertEquals(RpcStatusCode.OVERLOADED.getCode(), rejected.getCode());
            assertEquals("second", rejected.getRequestId());
            assertEquals(1, admission.inFlight(), "rejection must not consume another permit");
        } finally {
            admission.release();
            held.forEach(ReferenceCountUtil::release);
            channel.finishAndReleaseAll();
        }
        assertEquals(0, admission.inFlight());
    }

    @Test
    void successfulAndFailedBusinessCallsReleasePermitForNextCall() {
        RpcAdmissionHandler admission = new RpcAdmissionHandler(1);
        EmbeddedChannel channel = new EmbeddedChannel(admission, new NettyRpcServerHandler(admission));
        try {
            channel.writeInbound(message("success", "ok"));
            assertEquals(0, admission.inFlight());
            assertEquals(RpcStatusCode.SUCCESS.getCode(), response(channel).getCode());

            channel.writeInbound(message("failure", "fail"));
            assertEquals(0, admission.inFlight());
            assertEquals(RpcStatusCode.PROVIDER_EXCEPTION.getCode(), response(channel).getCode());

            channel.writeInbound(message("after-failure", "ok"));
            assertEquals(0, admission.inFlight());
            assertEquals(RpcStatusCode.SUCCESS.getCode(), response(channel).getCode());
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void heartbeatDoesNotConsumeRequestPermit() {
        RpcAdmissionHandler admission = new RpcAdmissionHandler(1);
        EmbeddedChannel channel = new EmbeddedChannel(admission, new NettyRpcServerHandler(admission));
        try {
            channel.writeInbound(RpcMessage.builder()
                    .messageType(RpcConstants.HEARTBEAT_REQUEST_TYPE).build());
            assertEquals(0, admission.inFlight());
            assertEquals(RpcConstants.HEARTBEAT_RESPONSE_TYPE,
                    ((RpcMessage) channel.readOutbound()).getMessageType());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static RpcMessage message(String requestId, String method) {
        RpcRequest request = RpcRequest.builder()
                .interfaceName(TestService.class.getName()).methodName(method)
                .requestId(requestId).group("admission-test").version("v1")
                .parameters(new Object[0]).paramTypes(new Class<?>[0]).build();
        return RpcMessage.builder().messageType(RpcConstants.REQUEST_TYPE).data(request).build();
    }

    private static RpcResponse<?> response(EmbeddedChannel channel) {
        return (RpcResponse<?>) ((RpcMessage) channel.readOutbound()).getData();
    }

    public interface TestService {
        String ok();

        String fail();
    }

    public static class TestServiceImpl implements TestService {
        @Override
        public String ok() {
            return "ok";
        }

        @Override
        public String fail() {
            throw new IllegalStateException("expected failure");
        }
    }
}
