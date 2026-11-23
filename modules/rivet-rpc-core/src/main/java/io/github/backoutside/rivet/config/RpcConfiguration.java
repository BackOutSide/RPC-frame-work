package io.github.backoutside.rivet.config;

import org.springframework.core.env.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Runtime RPC settings. The legacy rpc.properties file is read once; a Spring
 * Environment, when available, takes precedence over it.
 */
public final class RpcConfiguration {
    private static final Properties LEGACY_PROPERTIES = loadLegacyProperties();
    private static volatile RpcConfiguration current = new RpcConfiguration(null);

    private final String zkAddress;
    private final int zkCacheMaxStaleMs;
    private final String bindHost;
    private final String advertiseHost;
    private final int serverPort;
    private final int connectTimeoutMs;
    private final int requestTimeoutMs;
    private final int perAttemptTimeoutMs;
    private final String loadBalance;
    private final int maxAttempts;
    private final int maxConcurrentCalls;
    private final int circuitMinimumCalls;
    private final int circuitWindowSize;
    private final int circuitOpenMs;
    private final int serverMaxInflight;

    private RpcConfiguration(Environment environment) {
        zkAddress = property(environment, "rpc.zk.address", "rpc.zookeeper.address", "127.0.0.1:2181");
        zkCacheMaxStaleMs = integerProperty(environment, "rpc.zk.cache.max-stale-ms", 10000, 0, Integer.MAX_VALUE);
        bindHost = property(environment, "rpc.server.bind-host", null, "0.0.0.0");
        advertiseHost = property(environment, "rpc.server.advertise-host", null, "127.0.0.1");
        serverPort = integerProperty(environment, "rpc.server.port", 9998, 0, 65535);
        connectTimeoutMs = integerProperty(environment, "rpc.client.connect-timeout-ms", 5000, 1, Integer.MAX_VALUE);
        requestTimeoutMs = integerProperty(environment, "rpc.client.timeout-ms", 3000, 1, Integer.MAX_VALUE);
        perAttemptTimeoutMs = integerProperty(environment, "rpc.client.per-attempt-timeout-ms", 0, 0, Integer.MAX_VALUE);
        loadBalance = property(environment, "rpc.client.load-balance", null, "random");
        maxAttempts = integerProperty(environment, "rpc.client.max-attempts", 1, 1, 3);
        maxConcurrentCalls = integerProperty(environment, "rpc.client.max-concurrent-calls", 100, 1, 100000);
        circuitMinimumCalls = integerProperty(environment, "rpc.client.circuit.minimum-calls", 10, 1, 10000);
        circuitWindowSize = integerProperty(environment, "rpc.client.circuit.window-size", 20, circuitMinimumCalls, 10000);
        circuitOpenMs = integerProperty(environment, "rpc.client.circuit.open-ms", 5000, 1, Integer.MAX_VALUE);
        serverMaxInflight = integerProperty(environment, "rpc.server.max-inflight", 256, 1, 100000);
    }

    public static RpcConfiguration current() {
        return current;
    }

    public static void configure(Environment environment) {
        current = new RpcConfiguration(environment);
    }

    private static String property(Environment environment, String name, String legacyName, String defaultValue) {
        String value = environment == null ? null : environment.getProperty(name);
        if (value == null && legacyName != null && environment != null) {
            value = environment.getProperty(legacyName);
        }
        if (value == null) {
            value = LEGACY_PROPERTIES.getProperty(name);
        }
        if (value == null && legacyName != null) {
            value = LEGACY_PROPERTIES.getProperty(legacyName);
        }
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    private static int integerProperty(Environment environment, String name, int defaultValue, int min, int max) {
        String value = property(environment, name, null, Integer.toString(defaultValue));
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid RPC property " + name + "=" + value, ex);
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException("RPC property out of range " + name + "=" + value);
        }
        return parsed;
    }

    private static Properties loadLegacyProperties() {
        Properties properties = new Properties();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = RpcConfiguration.class.getClassLoader();
        }
        try (InputStream in = loader.getResourceAsStream("rpc.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not load rpc.properties", ex);
        }
        return properties;
    }

    public String getZkAddress() { return zkAddress; }
    public int getZkCacheMaxStaleMs() { return zkCacheMaxStaleMs; }
    public String getBindHost() { return bindHost; }
    public String getAdvertiseHost() { return advertiseHost; }
    public int getServerPort() { return serverPort; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    public int getPerAttemptTimeoutMs() { return perAttemptTimeoutMs; }
    public String getLoadBalance() { return loadBalance; }
    public int getMaxAttempts() { return maxAttempts; }
    public int getMaxConcurrentCalls() { return maxConcurrentCalls; }
    public int getCircuitMinimumCalls() { return circuitMinimumCalls; }
    public int getCircuitWindowSize() { return circuitWindowSize; }
    public int getCircuitOpenMs() { return circuitOpenMs; }
    public int getServerMaxInflight() { return serverMaxInflight; }
}
