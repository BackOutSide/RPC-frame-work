package io.github.backoutside.rivet.annotation;


import java.lang.annotation.*;

/**
 * 用于标注服务实现类
 * 
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Inherited
public @interface RpcService {

    /** Select the exported interface when an implementation has several. */
    Class<?> interfaceClass() default void.class;

    /**
     * Service version, default value is empty string
     */
    String version() default "";

    /**
     * Service group, default value is empty string
     */
    String group() default "";

}
