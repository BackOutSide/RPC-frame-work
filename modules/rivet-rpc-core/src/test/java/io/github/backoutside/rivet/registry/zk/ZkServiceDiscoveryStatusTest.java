package io.github.backoutside.rivet.registry.zk;

import io.github.backoutside.rivet.config.RpcConfiguration;
import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.exception.RpcUnavailableException;
import io.github.backoutside.rivet.registry.zk.util.CuratorUtils;
import io.github.backoutside.rivet.remoting.dto.RpcRequest;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZkServiceDiscoveryStatusTest {
    @Test
    void absentServicePathMeansNoProvider() throws Exception {
        try (TestingServer server = new TestingServer()) {
            configure(server);
            RpcRequest request = request("missing.Service", "missing-path");

            RpcUnavailableException error = assertThrows(RpcUnavailableException.class,
                    () -> new ZkServiceDiscoveryImpl().lookupServices(request));

            assertEquals(RpcStatusCode.NO_PROVIDER.getCode(), error.getCode());
            assertEquals(request.getRequestId(), error.getRequestId());
        } finally {
            reset();
        }
    }

    @Test
    void unreadableServicePathMeansDiscoveryFailed() throws Exception {
        try (TestingServer server = new TestingServer()) {
            configure(server);
            try (CuratorFramework admin = CuratorFrameworkFactory.builder()
                    .connectString(server.getConnectString())
                    .authorization("digest", "rpc-admin:secret".getBytes(StandardCharsets.UTF_8))
                    .retryPolicy(new RetryOneTime(100))
                    .build()) {
                admin.start();
                assertTrue(admin.blockUntilConnected(5, TimeUnit.SECONDS));
                admin.create().creatingParentsIfNeeded().forPath(CuratorUtils.ZK_REGISTER_ROOT_PATH);
                admin.create().withACL(ZooDefs.Ids.CREATOR_ALL_ACL)
                        .forPath(CuratorUtils.ZK_REGISTER_ROOT_PATH + "/restricted.Service");

                RpcRequest request = request("restricted.Service", "unreadable-path");
                RpcUnavailableException error = assertThrows(RpcUnavailableException.class,
                        () -> new ZkServiceDiscoveryImpl().lookupServices(request));

                assertEquals(RpcStatusCode.DISCOVERY_FAILED.getCode(), error.getCode());
                assertEquals(request.getRequestId(), error.getRequestId());
                assertInstanceOf(KeeperException.NoAuthException.class,
                        error.getCause().getCause());
            }
        } finally {
            reset();
        }
    }

    private static RpcRequest request(String interfaceName, String requestId) {
        return RpcRequest.builder().interfaceName(interfaceName).group("").version("")
                .requestId(requestId).build();
    }

    private static void configure(TestingServer server) {
        CuratorUtils.closeZkClient();
        System.setProperty("rpc.zk.address", server.getConnectString());
        RpcConfiguration.configure(new StandardEnvironment());
    }

    private static void reset() {
        CuratorUtils.closeZkClient();
        System.clearProperty("rpc.zk.address");
        RpcConfiguration.configure(new StandardEnvironment());
    }
}
