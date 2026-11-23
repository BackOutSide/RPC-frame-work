package io.github.backoutside.rivet.registry;

import io.github.backoutside.rivet.DemoRpcService;
import io.github.backoutside.rivet.DemoRpcServiceImpl;
import io.github.backoutside.rivet.config.RpcConfiguration;
import io.github.backoutside.rivet.config.RpcServiceConfig;
import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.exception.RpcUnavailableException;
import io.github.backoutside.rivet.registry.zk.ZkServiceDiscoveryImpl;
import io.github.backoutside.rivet.registry.zk.ZkServiceRegistryImpl;
import io.github.backoutside.rivet.registry.zk.util.CuratorUtils;
import io.github.backoutside.rivet.remoting.dto.RpcRequest;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.test.KillSession;
import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.data.Stat;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZkServiceRegistryImplTest {
    @Test
    void registersAndDiscoversServiceUsingEmbeddedZooKeeper() throws Exception {
        try (TestingServer server = new TestingServer()) {
            System.setProperty("rpc.zk.address", server.getConnectString());
            RpcConfiguration.configure(new StandardEnvironment());
            RpcServiceConfig service = RpcServiceConfig.builder()
                    .group("registry-test").version("v1")
                    .service(new DemoRpcServiceImpl()).build();
            InetSocketAddress expected = new InetSocketAddress("127.0.0.1", 9333);
            new ZkServiceRegistryImpl().registerService(service.getRpcServiceName(), expected);

            RpcRequest request = RpcRequest.builder()
                    .interfaceName(DemoRpcService.class.getName())
                    .group(service.getGroup()).version(service.getVersion())
                    .requestId("registry-test-request").build();
            InetSocketAddress actual = new ZkServiceDiscoveryImpl().lookupService(request);
            assertEquals(expected, actual);
            CuratorUtils.closeZkClient();
        } finally {
            CuratorUtils.closeZkClient();
            System.clearProperty("rpc.zk.address");
            RpcConfiguration.configure(new StandardEnvironment());
        }
    }

    @Test
    void cachedProvidersFailClosedAfterTheirMaximumAgeDuringZooKeeperOutage() throws Exception {
        try (TestingServer server = new TestingServer()) {
            System.setProperty("rpc.zk.address", server.getConnectString());
            System.setProperty("rpc.zk.cache.max-stale-ms", "2500");
            RpcConfiguration.configure(new StandardEnvironment());

            String service = "cache-test-" + UUID.randomUUID();
            InetSocketAddress address = new InetSocketAddress("127.0.0.1", 9334);
            new ZkServiceRegistryImpl().registerService(service, address);
            CuratorFramework client = CuratorUtils.getZkClient();
            List<String> initial = CuratorUtils.getChildrenNodes(client, service);
            assertEquals(List.of("127.0.0.1:9334"), initial);

            server.stop();
            assertEquals(initial, CuratorUtils.getChildrenNodes(client, service),
                    "a recent successful discovery may survive a brief registry outage");
            Thread.sleep(2600);

            RpcUnavailableException stale = assertThrows(RpcUnavailableException.class,
                    () -> CuratorUtils.getChildrenNodes(client, service));
            assertEquals(RpcStatusCode.DISCOVERY_FAILED.getCode(), stale.getCode());
            RpcUnavailableException uncached = assertThrows(RpcUnavailableException.class,
                    () -> CuratorUtils.getChildrenNodes(client, "never-seen-" + service));
            assertEquals(RpcStatusCode.DISCOVERY_FAILED.getCode(), uncached.getCode());
            server.restart();
            long reconnectDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!client.getZookeeperClient().isConnected() && System.nanoTime() < reconnectDeadline) {
                Thread.sleep(50);
            }
            assertTrue(client.getZookeeperClient().isConnected());
            CuratorUtils.closeZkClient();
        } finally {
            CuratorUtils.closeZkClient();
            System.clearProperty("rpc.zk.address");
            System.clearProperty("rpc.zk.cache.max-stale-ms");
            RpcConfiguration.configure(new StandardEnvironment());
        }
    }

    @Test
    void managedEphemeralRegistrationRecoversAfterSessionExpires() throws Exception {
        try (TestingServer server = new TestingServer()) {
            System.setProperty("rpc.zk.address", server.getConnectString());
            RpcConfiguration.configure(new StandardEnvironment());

            String service = "session-test-" + UUID.randomUUID();
            InetSocketAddress address = new InetSocketAddress("127.0.0.1", 9335);
            String path = CuratorUtils.ZK_REGISTER_ROOT_PATH + "/" + service + "/127.0.0.1:9335";
            new ZkServiceRegistryImpl().registerService(service, address);
            CuratorFramework client = CuratorUtils.getZkClient();
            Stat registered = client.checkExists().forPath(path);
            assertNotNull(registered);
            long originalOwner = registered.getEphemeralOwner();
            assertNotEquals(0, originalOwner);
            long originalSession = client.getZookeeperClient().getZooKeeper().getSessionId();

            KillSession.kill(client.getZookeeperClient().getZooKeeper());
            long sessionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < sessionDeadline
                    && client.getZookeeperClient().getZooKeeper().getSessionId() == originalSession) {
                Thread.sleep(100);
            }
            assertNotEquals(originalSession, client.getZookeeperClient().getZooKeeper().getSessionId(),
                    "Curator must establish a new session after expiration");
            // The test hook injects an expired event into the client but does not
            // delete the old server-side ephemeral node. Complete that server step.
            Stat oldNode = client.checkExists().forPath(path);
            if (oldNode != null && oldNode.getEphemeralOwner() == originalOwner) {
                client.delete().forPath(path);
            }
            boolean recreated = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline) {
                try {
                    Stat current = client.checkExists().forPath(path);
                    if (current != null && current.getEphemeralOwner() != originalOwner) {
                        recreated = true;
                        break;
                    }
                } catch (Exception ignored) {
                    // A session transition can make an individual read fail briefly.
                }
                Thread.sleep(100);
            }
            assertTrue(recreated, "Curator must recreate the desired provider node after session loss");

            CuratorUtils.clearRegistry(client, address);
            assertNull(client.checkExists().forPath(path));
            CuratorUtils.closeZkClient();
        } finally {
            CuratorUtils.closeZkClient();
            System.clearProperty("rpc.zk.address");
            RpcConfiguration.configure(new StandardEnvironment());
        }
    }

    @Test
    void hostnameAndIpv6ProviderAddressesRoundTripWithoutExternalDns() throws Exception {
        try (TestingServer server = new TestingServer()) {
            System.setProperty("rpc.zk.address", server.getConnectString());
            RpcConfiguration.configure(new StandardEnvironment());

            String hostnameService = "hostname-test-" + UUID.randomUUID();
            String ipv6Service = "ipv6-test-" + UUID.randomUUID();
            new ZkServiceRegistryImpl().registerService(hostnameService,
                    InetSocketAddress.createUnresolved("localhost", 9336));
            new ZkServiceRegistryImpl().registerService(ipv6Service,
                    InetSocketAddress.createUnresolved("::1", 9337));

            CuratorFramework client = CuratorUtils.getZkClient();
            assertEquals(List.of("localhost:9336"), CuratorUtils.getChildrenNodes(client, hostnameService));
            assertEquals(List.of("[::1]:9337"), CuratorUtils.getChildrenNodes(client, ipv6Service));

            InetSocketAddress hostname = new ZkServiceDiscoveryImpl().lookupService(request(hostnameService));
            InetSocketAddress ipv6 = new ZkServiceDiscoveryImpl().lookupService(request(ipv6Service));
            assertEquals(9336, hostname.getPort());
            assertTrue(hostname.getAddress().isLoopbackAddress());
            assertEquals(9337, ipv6.getPort());
            assertTrue(ipv6.getAddress().isLoopbackAddress());
            CuratorUtils.closeZkClient();
        } finally {
            CuratorUtils.closeZkClient();
            System.clearProperty("rpc.zk.address");
            RpcConfiguration.configure(new StandardEnvironment());
        }
    }

    private static RpcRequest request(String service) {
        return RpcRequest.builder().interfaceName(service).group("").version("")
                .requestId("address-test-request").build();
    }
}
