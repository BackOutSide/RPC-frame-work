package io.github.backoutside.rivet;

import io.github.backoutside.rivet.extension.ExtensionLoader;
import io.github.backoutside.rivet.loadbalance.LoadBalance;
import io.github.backoutside.rivet.serialize.Serializer;
import io.github.backoutside.rivet.serialize.jdk.JavaSerializer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * @author: Zekun Fu
 * @date: 2025/6/8 19:58
 * @Description:
 */
@Slf4j
public class ExtentionTest {


    @Test
    public void testExtention() {
        LoadBalance loadBalance = ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension("loadBalanceNew");
    }

    @Test
    public void jdkSerializerDescriptorResolves() {
        Serializer serializer = ExtensionLoader.getExtensionLoader(Serializer.class).getExtension("jdk");
        assertInstanceOf(JavaSerializer.class, serializer);
    }
}
