package io.github.backoutside.rivet.remoting.transport.netty.client;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * store and get Channel object
 *
 */
@Slf4j
public class ChannelProvider {
    // channel Map 建立InetSocketAddress与Channel的映射关系
    private final Map<String, Channel> channelMap;

    /**
     * 初始化通道Map
     */
    public ChannelProvider() {
        channelMap = new ConcurrentHashMap<>();
    }

    /**
     * 获取通道
     * @param inetSocketAddress
     * @return
     */
    public Channel get(InetSocketAddress inetSocketAddress) {
        String key = inetSocketAddress.toString();
        Channel channel = channelMap.get(key);
        if (channel == null) {
            return null;
        }
        if (channel.isActive()) {
            return channel;
        }
        // A concurrent connect may already have replaced this inactive channel.
        channelMap.remove(key, channel);
        return null;
    }

    /**
     * 设置通道
     * @param inetSocketAddress
     * @param channel
     */
    public void set(InetSocketAddress inetSocketAddress, Channel channel) {
        String key = inetSocketAddress.toString();
        channelMap.put(key, channel);
    }

    /**
     * 移除通道
     * @param inetSocketAddress
     */
    public void remove(InetSocketAddress inetSocketAddress) {
        String key = inetSocketAddress.toString();
        channelMap.remove(key);
        log.info("Channel map size :[{}]", channelMap.size());
    }

    public void closeAll() {
        for (Channel channel : channelMap.values()) {
            channel.close();
        }
        channelMap.clear();
    }
}
