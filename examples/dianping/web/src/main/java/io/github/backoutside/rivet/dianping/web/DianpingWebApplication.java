package io.github.backoutside.rivet.dianping.web;

import io.github.backoutside.rivet.annotation.RpcScan;
import io.github.backoutside.rivet.dianping.web.session.DemoUsersProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@RpcScan(basePackage = {"io.github.backoutside.rivet.dianping"})
@EnableConfigurationProperties(DemoUsersProperties.class)
public class DianpingWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(DianpingWebApplication.class, args);
    }
}
