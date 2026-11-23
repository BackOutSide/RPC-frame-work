package io.github.backoutside.rivet.dianping.probe;

import org.apache.curator.test.InstanceSpec;
import org.apache.curator.test.TestingServer;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** Standalone, test-scope ZooKeeper for the local RPC monitoring evaluation. */
public final class EvaluationZooKeeper {
    private EvaluationZooKeeper() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 12181 : Integer.parseInt(args[0]);
        if (args.length > 1 || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Usage: EvaluationZooKeeper [port 1..65535]");
        }
        InstanceSpec loopback = new InstanceSpec(null, port, -1, -1, true, -1, -1, -1,
                Map.of("clientPortAddress", "127.0.0.1", "admin.enableServer", "false"), "127.0.0.1");
        try (TestingServer server = new TestingServer(loopback, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.close();
                } catch (IOException ex) {
                    System.err.println("Could not close evaluation ZooKeeper: " + ex.getMessage());
                }
            }, "evaluation-zookeeper-shutdown"));
            System.out.println("RIVET_EVALUATION_ZK_READY " + server.getConnectString());
            new CountDownLatch(1).await();
        }
    }
}
