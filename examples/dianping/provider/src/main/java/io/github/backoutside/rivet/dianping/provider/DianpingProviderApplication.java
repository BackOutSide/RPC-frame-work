package io.github.backoutside.rivet.dianping.provider;

import io.github.backoutside.rivet.annotation.RpcScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@RpcScan(basePackage = {"io.github.backoutside.rivet.dianping.provider"})
public class DianpingProviderApplication {
    public static void main(String[] args) {
        SpringApplication.run(DianpingProviderApplication.class, args);
    }
}
