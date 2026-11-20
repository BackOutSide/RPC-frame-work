package io.github.backoutside.rivet.spring;

import io.github.backoutside.rivet.annotation.RpcScan;
import io.github.backoutside.rivet.annotation.RpcService;
import io.github.backoutside.rivet.remoting.transport.netty.server.NettyRpcServer;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.stereotype.Component;

/** Registers RPC services and the framework's Spring integration beans. */
public class CustomScannerRegistrar implements ImportBeanDefinitionRegistrar, ResourceLoaderAware {
    private ResourceLoader resourceLoader;

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        AnnotationAttributes attributes = AnnotationAttributes.fromMap(
                metadata.getAnnotationAttributes(RpcScan.class.getName()));
        String[] packages = attributes == null ? new String[0] : attributes.getStringArray("basePackage");
        if (packages.length == 0) {
            String className = metadata.getClassName();
            int lastDot = className.lastIndexOf('.');
            packages = new String[]{lastDot < 0 ? "" : className.substring(0, lastDot)};
        }

        CustomScanner serviceScanner = new CustomScanner(registry, RpcService.class);
        if (resourceLoader != null) {
            serviceScanner.setResourceLoader(resourceLoader);
        }
        serviceScanner.scan(packages);

        // Plain Spring demos use @RpcScan without @ComponentScan. Boot applications
        // already scan their own components, so avoid a second application scan.
        if (!isBootApplication(metadata)) {
            CustomScanner componentScanner = new CustomScanner(registry, Component.class);
            if (resourceLoader != null) {
                componentScanner.setResourceLoader(resourceLoader);
            }
            componentScanner.scan(packages);
        }

        registerIfMissing(registry, SpringBeanPostProcessor.class);
        registerIfMissing(registry, NettyRpcServer.class);
    }

    private boolean isBootApplication(AnnotationMetadata metadata) {
        return metadata.hasAnnotation("org.springframework.boot.autoconfigure.SpringBootApplication")
                || metadata.hasMetaAnnotation("org.springframework.boot.autoconfigure.SpringBootApplication");
    }

    private void registerIfMissing(BeanDefinitionRegistry registry, Class<?> beanClass) {
        for (String name : registry.getBeanDefinitionNames()) {
            BeanDefinition definition = registry.getBeanDefinition(name);
            if (beanClass.getName().equals(definition.getBeanClassName())) {
                return;
            }
        }
        registry.registerBeanDefinition(beanClass.getName(), new RootBeanDefinition(beanClass));
    }
}
