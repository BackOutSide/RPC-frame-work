package github.javaguide.spring;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.annotation.Annotation;

/**
 *  自定义扫描器
 *  ClassPathBeanDefinitionScanner: 1.扫描指定包下的所有类 2.根据默认或自定义的过滤规则决定是否注册为Bean
 *
 */
public class CustomScanner extends ClassPathBeanDefinitionScanner {

    /**
     * 构造函数
     * @param registry BeanDefinition注册表
     * @param annoType 注解类型
     */
    public CustomScanner(BeanDefinitionRegistry registry, Class<? extends Annotation> annoType) {
        super(registry);
        super.addIncludeFilter(new AnnotationTypeFilter(annoType));
    }

    /**
     * 扫描指定包下的所有类
     * @param basePackages 包名
     * @return 扫描到的类数
     */
    @Override
    public int scan(String... basePackages) {
        return super.scan(basePackages);
    }
}