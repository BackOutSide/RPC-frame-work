package io.github.backoutside.rivet.registry;

import io.github.backoutside.rivet.DemoRpcService;
import io.github.backoutside.rivet.DemoRpcServiceImpl;
import io.github.backoutside.rivet.config.RpcConfiguration;
import io.github.backoutside.rivet.config.RpcServiceConfig;
import io.github.backoutside.rivet.registry.zk.ZkServiceDiscoveryImpl;
import io.github.backoutside.rivet.registry.zk.ZkServiceRegistryImpl;
import io.github.backoutside.rivet.registry.zk.util.CuratorUtils;
import io.github.backoutside.rivet.remoting.dto.RpcRequest;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        } finally {
            CuratorUtils.closeZkClient();
            System.clearProperty("rpc.zk.address");
            RpcConfiguration.configure(new StandardEnvironment());
        }
    }
}
