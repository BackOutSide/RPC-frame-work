package io.github.backoutside.rivet.dianping.web;

import io.github.backoutside.rivet.metrics.RpcMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the framework's global Micrometer meters reach a Boot Actuator scrape endpoint. */
class RpcMetricsPrometheusExportTest {
    @Test
    void exportsRpcMetersThroughActuatorWithoutExternalServices() throws Exception {
        try (ServletWebServerApplicationContext context =
                     (ServletWebServerApplicationContext) new SpringApplicationBuilder(MetricsHost.class)
                             .web(WebApplicationType.SERVLET)
                             .properties("spring.main.banner-mode=off")
                             .run("--server.address=127.0.0.1", "--server.port=0")) {
            String service = "test.TelemetryService";
            RpcMetrics.client(service, "fetch", "NettyRpcClient", "success",
                    Duration.ofMillis(125).toNanos());
            RpcMetrics.server(service, "fetch", "success", Duration.ofMillis(100).toNanos());
            RpcMetrics.attempt(service, "success");
            RpcMetrics.retry(service);
            RpcMetrics.rejection(service, "bulkhead");

            int port = context.getWebServer().getPort();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/actuator/prometheus"))
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            String metrics = response.body();
            assertTrue(metrics.contains("rivet_rpc_client_duration_seconds_count{"), metrics);
            assertTrue(metrics.contains("rivet_rpc_client_duration_seconds_bucket{"), metrics);
            assertTrue(metrics.contains("rivet_rpc_server_duration_seconds_count{"), metrics);
            assertTrue(metrics.contains("rivet_rpc_client_attempts_total{"), metrics);
            assertTrue(metrics.contains("rivet_rpc_client_retries_total{"), metrics);
            assertTrue(metrics.contains("rivet_rpc_client_rejections_total{"), metrics);
            assertTrue(metrics.contains("service=\"" + service + "\""), metrics);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
    static class MetricsHost {
    }
}
