package io.github.backoutside.rivet.dianping.probe;

import io.github.backoutside.rivet.annotation.RpcScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
// Boot's component scan selects the fixture roles using the active profile.
@RpcScan(basePackage = "io.github.backoutside.rivet.dianping.api")
public class DianpingRpcProbeApplication {
    public static void main(String[] args) {
        SpringApplication.run(DianpingRpcProbeApplication.class, args);
    }
}
