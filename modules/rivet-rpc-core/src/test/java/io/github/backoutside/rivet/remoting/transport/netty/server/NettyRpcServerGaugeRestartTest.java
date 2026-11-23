package io.github.backoutside.rivet.remoting.transport.netty.server;

import io.github.backoutside.rivet.config.RpcConfiguration;
import io.github.backoutside.rivet.config.RpcServiceConfig;
import io.github.backoutside.rivet.provider.impl.ZkServiceProviderImpl;
import io.github.backoutside.rivet.registry.zk.util.CuratorUtils;
import io.github.backoutside.rivet.remoting.constants.RpcConstants;
import io.github.backoutside.rivet.remoting.dto.RpcMessage;
import io.github.backoutside.rivet.remoting.dto.RpcRequest;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class NettyRpcServerGaugeRestartTest {
    @Test
    void inflightGaugeObservesNewAdmissionAfterStopAndRestart() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (TestingServer zk = new TestingServer()) {
            Metrics.addRegistry(registry);
            System.setProperty("rpc.zk.address", zk.getConnectString());
            System.setProperty("rpc.server.bind-host", "127.0.0.1");
            System.setProperty("rpc.server.advertise-host", "127.0.0.1");
            System.setProperty("rpc.server.port", "0");
            System.setProperty("rpc.server.max-inflight", "1");
            RpcConfiguration.configure(new StandardEnvironment());

            RpcServiceConfig config = RpcServiceConfig.builder()
                    .service(new TestServiceImpl()).interfaceClass(TestService.class)
                    .group("gauge-test-" + UUID.randomUUID()).version("v1").build();
            NettyRpcServer server = new NettyRpcServer();
            try {
                server.registerService(config);
                server.start();
                Gauge gauge = registry.find("rivet.rpc.server.inflight").gauge();
                assertNotNull(gauge);
                RpcAdmissionHandler first = currentAdmission(server);
                assertNotNull(first);
                assertEquals(0.0, gauge.value());
                assertGaugeTracksAdmission(first, gauge);

                server.stop();
                assertEquals(0.0, gauge.value());
                server.start();
                RpcAdmissionHandler second = currentAdmission(server);
                assertNotNull(second);
                assertNotSame(first, second, "restart must create a new admission handler");
                assertSame(gauge, registry.find("rivet.rpc.server.inflight").gauge(),
                        "restart must keep the existing meter");
                assertGaugeTracksAdmission(second, gauge);
            } finally {
                server.stop();
                removeTestService(config.getRpcServiceName());
                CuratorUtils.closeZkClient();
                System.clearProperty("rpc.zk.address");
                System.clearProperty("rpc.server.bind-host");
                System.clearProperty("rpc.server.advertise-host");
                System.clearProperty("rpc.server.port");
                System.clearProperty("rpc.server.max-inflight");
                RpcConfiguration.configure(new StandardEnvironment());
                Metrics.removeRegistry(registry);
            }
        } finally {
            registry.close();
        }
    }

    private static void assertGaugeTracksAdmission(RpcAdmissionHandler admission, Gauge gauge) {
        EmbeddedChannel channel = new EmbeddedChannel(admission);
        try {
            RpcRequest request = RpcRequest.builder()
                    .requestId(UUID.randomUUID().toString())
                    .interfaceName(TestService.class.getName())
                    .methodName("test").group("").version("")
                    .parameters(new Object[0]).paramTypes(new Class<?>[0]).build();
            channel.writeInbound(RpcMessage.builder()
                    .messageType(RpcConstants.REQUEST_TYPE).data(request).build());
            assertEquals(1, admission.inFlight());
            assertEquals(1.0, gauge.value(), "gauge must read the currently active admission handler");
        } finally {
            if (admission.inFlight() > 0) {
                admission.release();
            }
            channel.finishAndReleaseAll();
        }
        assertEquals(0.0, gauge.value());
    }

    private static RpcAdmissionHandler currentAdmission(NettyRpcServer server) throws Exception {
        Field field = NettyRpcServer.class.getDeclaredField("admission");
        field.setAccessible(true);
        return (RpcAdmissionHandler) field.get(server);
    }

    private static void removeTestService(String name) throws Exception {
        ZkServiceProviderImpl provider = ZkServiceProviderImpl.getInstance();
        Field services = ZkServiceProviderImpl.class.getDeclaredField("serviceMap");
        Field registered = ZkServiceProviderImpl.class.getDeclaredField("registeredService");
        services.setAccessible(true);
        registered.setAccessible(true);
        ((Map<?, ?>) services.get(provider)).remove(name);
        ((Set<?>) registered.get(provider)).remove(name);
    }

    private interface TestService {
    }

    private static final class TestServiceImpl implements TestService {
    }
}
