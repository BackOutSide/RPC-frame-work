package io.github.backoutside.rivet.remoting.transport.netty.client;

import io.github.backoutside.rivet.exception.RpcTimeoutException;
import io.github.backoutside.rivet.exception.RpcUnavailableException;
import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyRpcClientConnectionTest {
    @Test
    void shortCallerTimeoutDoesNotCancelConnectionNeededByLongCaller() throws Exception {
        InetSocketAddress address = InetSocketAddress.createUnresolved("shared-connection-test", 19001);
        EmbeddedChannel channel = new EmbeddedChannel();
        DefaultChannelPromise connection = new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE);
        CountDownLatch connectStarted = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        NettyRpcClient client = new NettyRpcClient(ignored -> {
            connectStarted.countDown();
            return connection;
        });
        try {
            Future<Throwable> shortCall = callers.submit(() -> {
                try {
                    client.getChannel(address, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250), "short");
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertTrue(connectStarted.await(1, TimeUnit.SECONDS));
            CountDownLatch longCallStarted = new CountDownLatch(1);
            Future<Channel> longCall = callers.submit(() -> {
                longCallStarted.countDown();
                return client.getChannel(address, System.nanoTime() + TimeUnit.SECONDS.toNanos(2), "long");
            });
            assertTrue(longCallStarted.await(1, TimeUnit.SECONDS));

            assertInstanceOf(RpcTimeoutException.class, shortCall.get(2, TimeUnit.SECONDS));
            assertFalse(connection.isCancelled(), "one caller's deadline must not cancel shared connect");
            assertFalse(connection.isDone(), "the connect remains pending for the second caller");

            connection.setSuccess();
            assertSame(channel, longCall.get(2, TimeUnit.SECONDS));
        } finally {
            client.close();
            callers.shutdownNow();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void closingClientCancelsPendingConnectionAndWakesCaller() throws Exception {
        InetSocketAddress address = InetSocketAddress.createUnresolved("close-connection-test", 19002);
        EmbeddedChannel channel = new EmbeddedChannel();
        DefaultChannelPromise connection = new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE);
        CountDownLatch connectStarted = new CountDownLatch(1);
        ExecutorService callers = Executors.newSingleThreadExecutor();
        NettyRpcClient client = new NettyRpcClient(ignored -> {
            connectStarted.countDown();
            return connection;
        });
        try {
            Future<Throwable> call = callers.submit(() -> {
                try {
                    client.getChannel(address, System.nanoTime() + TimeUnit.SECONDS.toNanos(2), "closing");
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertTrue(connectStarted.await(1, TimeUnit.SECONDS));

            client.close();

            assertInstanceOf(RpcUnavailableException.class, call.get(1, TimeUnit.SECONDS));
            assertTrue(connection.isCancelled(), "closing the client must cancel its pending connect");
        } finally {
            client.close();
            callers.shutdownNow();
            channel.finishAndReleaseAll();
        }
    }
}
