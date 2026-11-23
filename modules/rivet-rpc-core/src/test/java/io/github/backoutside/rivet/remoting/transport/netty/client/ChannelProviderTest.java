package io.github.backoutside.rivet.remoting.transport.netty.client;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelProviderTest {
    @Test
    void inactiveReadDoesNotRemoveConcurrentReplacement() throws Exception {
        ChannelProvider provider = new ChannelProvider();
        InetSocketAddress address = InetSocketAddress.createUnresolved("replace-channel-test", 19003);
        CountDownLatch checkedOldChannel = new CountDownLatch(1);
        CountDownLatch allowInactiveResult = new CountDownLatch(1);
        Channel old = (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(),
                new Class<?>[]{Channel.class}, (proxy, method, args) -> {
                    if (method.getName().equals("isActive")) {
                        checkedOldChannel.countDown();
                        allowInactiveResult.await();
                        return false;
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == args[0];
                    }
                    return null;
                });
        EmbeddedChannel replacement = new EmbeddedChannel();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            provider.set(address, old);
            Future<Channel> staleRead = reader.submit(() -> provider.get(address));
            assertTrue(checkedOldChannel.await(1, TimeUnit.SECONDS));
            provider.set(address, replacement);
            allowInactiveResult.countDown();

            assertNull(staleRead.get(1, TimeUnit.SECONDS));
            assertSame(replacement, provider.get(address));
        } finally {
            allowInactiveResult.countDown();
            reader.shutdownNow();
            provider.remove(address);
            replacement.finishAndReleaseAll();
        }
    }
}
