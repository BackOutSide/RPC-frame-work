package github.javaguide.spring;

import github.javaguide.annotation.RpcScan;
import github.javaguide.annotation.RpcService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.StandardAnnotationMetadata;
import org.springframework.stereotype.Component;

/**
 * 扫描并过滤指定注解
 * ImportBeanDefinitionRegistrar：用于在Spring容器中注册BeanDefinition
 * ResourceLoaderAware: 加载类路径资源
 * 
 */
@Slf4j
public class CustomScannerRegistrar implements ImportBeanDefinitionRegistrar, ResourceLoaderAware {

    //扫描范围
    private static final String SPRING_BEAN_BASE_PACKAGE = "github.javaguide";
    //扫描注解属性
    private static final String BASE_PACKAGE_ATTRIBUTE_NAME = "basePackage";
    //资源加载器
    private ResourceLoader resourceLoader;

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /**
     * 注册BeanDefinition
     * @param annotationMetadata 注解元数据
     * @param beanDefinitionRegistry BeanDefinition注册表
     */
    @Override
    public void registerBeanDefinitions(AnnotationMetadata annotationMetadata, BeanDefinitionRegistry beanDefinitionRegistry) {
        //1. 获取RpcScan注解的属性值
        AnnotationAttributes rpcScanAnnotationAttributes = AnnotationAttributes.fromMap(annotationMetadata.getAnnotationAttributes(RpcScan.class.getName()));
        String[] rpcScanBasePackages = new String[0];
        // 1.1 获取basePackage属性值
        if (rpcScanAnnotationAttributes != null) {
            rpcScanBasePackages = rpcScanAnnotationAttributes.getStringArray(BASE_PACKAGE_ATTRIBUTE_NAME);
        }
        // 1.2 如果basePackage属性值为空，则获取当前类的包名
        if (rpcScanBasePackages.length == 0) {
            rpcScanBasePackages = new String[]{((StandardAnnotationMetadata) annotationMetadata).getIntrospectedClass().getPackage().getName()};
        }
        // 1.3 扫描RpcService注解
        CustomScanner rpcServiceScanner = new CustomScanner(beanDefinitionRegistry, RpcService.class);
        // 1.4 扫描Component注解
        CustomScanner springBeanScanner = new CustomScanner(beanDefinitionRegistry, Component.class);
        // 1.5 如果资源加载器不为空，则设置资源加载器
        if (resourceLoader != null) {
            rpcServiceScanner.setResourceLoader(resourceLoader);
            springBeanScanner.setResourceLoader(resourceLoader);
        }
        // 1.6 扫描springBeanScanner
        int springBeanAmount = springBeanScanner.scan(SPRING_BEAN_BASE_PACKAGE);
        // 1.7 打印springBeanScanner扫描的数量
        log.info("springBeanScanner扫描的数量 [{}]", springBeanAmount);
        // 1.8 扫描rpcServiceScanner
        int rpcServiceCount = rpcServiceScanner.scan(rpcScanBasePackages);
        // 1.9 打印rpcServiceScanner扫描的数量
        log.info("rpcServiceScanner扫描的数量 [{}]", rpcServiceCount);
    }

}
