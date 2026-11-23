package io.github.backoutside.rivet.remoting.transport.netty.codec;

import io.github.backoutside.rivet.compress.gzip.GzipCompress;
import io.github.backoutside.rivet.enums.CompressTypeEnum;
import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.enums.SerializationTypeEnum;
import io.github.backoutside.rivet.exception.RpcException;
import io.github.backoutside.rivet.remoting.constants.RpcConstants;
import io.github.backoutside.rivet.remoting.dto.RpcMessage;
import io.github.backoutside.rivet.remoting.dto.RpcResponse;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RpcMessageCodecTest {
    @Test
    void hessianGzipResponseAndHeartbeatRoundTrip() {
        EmbeddedChannel channel = new EmbeddedChannel(new RpcMessageFrameDecoder(), new RpcMessageCodec());
        try {
            RpcMessage response = RpcMessage.builder()
                    .messageType(RpcConstants.RESPONSE_TYPE)
                    .codec(SerializationTypeEnum.HESSIAN.getCode())
                    .compress(CompressTypeEnum.GZIP.getCode())
                    .data(RpcResponse.success("hello", "req-1"))
                    .build();
            channel.writeOutbound(response);
            ByteBuf encoded = channel.readOutbound();
            channel.writeInbound(encoded);
            RpcMessage decoded = channel.readInbound();
            RpcResponse<?> body = assertInstanceOf(RpcResponse.class, decoded.getData());
            assertEquals("req-1", body.getRequestId());
            assertEquals("hello", body.getData());

            RpcMessage heartbeat = RpcMessage.builder()
                    .messageType(RpcConstants.HEARTBEAT_REQUEST_TYPE)
                    .codec(SerializationTypeEnum.HESSIAN.getCode())
                    .compress(CompressTypeEnum.GZIP.getCode())
                    .data(RpcConstants.PING)
                    .build();
            channel.writeOutbound(heartbeat);
            channel.writeInbound((ByteBuf) channel.readOutbound());
            RpcMessage decodedHeartbeat = channel.readInbound();
            assertEquals(RpcConstants.PING, decodedHeartbeat.getData());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void badMagicVersionAndMessageTypeAreProtocolErrors() {
        ByteBuf badMagic = heartbeatFrame();
        badMagic.setByte(0, '!');
        assertInboundStatus(RpcStatusCode.PROTOCOL_ERROR, badMagic);

        ByteBuf badVersion = heartbeatFrame();
        badVersion.setByte(4, RpcConstants.VERSION + 1);
        assertInboundStatus(RpcStatusCode.PROTOCOL_ERROR, badVersion);

        ByteBuf badType = heartbeatFrame();
        badType.setByte(9, 99);
        assertInboundStatus(RpcStatusCode.PROTOCOL_ERROR, badType);
    }

    @Test
    void invalidAndOversizedFrameLengthsAreProtocolErrors() {
        ByteBuf tooShort = heartbeatFrame();
        tooShort.setInt(5, RpcConstants.HEAD_LENGTH - 1);
        assertInboundStatus(RpcStatusCode.PROTOCOL_ERROR, tooShort);

        ByteBuf tooLong = Unpooled.buffer(9);
        tooLong.writeBytes(RpcConstants.MAGIC_NUMBER);
        tooLong.writeByte(RpcConstants.VERSION);
        tooLong.writeInt(RpcConstants.MAX_FRAME_LENGTH + 1);
        assertInboundStatus(RpcStatusCode.PROTOCOL_ERROR, tooLong);
    }

    @Test
    void unknownCodecAndCompressionAreCodecErrors() {
        ByteBuf unknownCodec = heartbeatFrame();
        unknownCodec.setByte(10, 99);
        assertInboundStatus(RpcStatusCode.CODEC_ERROR, unknownCodec);

        ByteBuf unknownCompress = heartbeatFrame();
        unknownCompress.setByte(11, 99);
        assertInboundStatus(RpcStatusCode.CODEC_ERROR, unknownCompress);
    }

    @Test
    void malformedCompressedBodyIsCodecError() {
        ByteBuf badBody = frame(RpcConstants.REQUEST_TYPE, new byte[]{1, 2, 3});
        assertInboundStatus(RpcStatusCode.CODEC_ERROR, badBody);
    }

    @Test
    void malformedSerializedBodyIsCodecError() {
        byte[] compressed = new GzipCompress().compress(new byte[]{1, 2, 3});
        assertInboundStatus(RpcStatusCode.CODEC_ERROR, frame(RpcConstants.REQUEST_TYPE, compressed));
    }

    @Test
    void unsupportedOutboundMessageTypeIsProtocolError() {
        EmbeddedChannel channel = new EmbeddedChannel(new RpcMessageCodec());
        try {
            RuntimeException failure = assertThrows(RuntimeException.class, () -> channel.writeOutbound(
                    RpcMessage.builder().messageType((byte) 99)
                            .codec(SerializationTypeEnum.HESSIAN.getCode())
                            .compress(CompressTypeEnum.GZIP.getCode()).build()));
            assertEquals(RpcStatusCode.PROTOCOL_ERROR, findRpcException(failure).getKnownStatusCode());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void assertInboundStatus(RpcStatusCode expected, ByteBuf frame) {
        EmbeddedChannel channel = new EmbeddedChannel(new RpcMessageFrameDecoder(), new RpcMessageCodec());
        try {
            RuntimeException failure = assertThrows(RuntimeException.class, () -> channel.writeInbound(frame));
            RpcException rpc = findRpcException(failure);
            assertEquals(expected, rpc.getKnownStatusCode());
            assertEquals(expected.getCode(), rpc.getCode());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static RpcException findRpcException(Throwable failure) {
        Throwable current = failure;
        while (current != null && !(current instanceof RpcException)) {
            current = current.getCause();
        }
        assertNotNull(current, "Expected a coded RpcException in the Netty cause chain");
        return (RpcException) current;
    }

    private static ByteBuf heartbeatFrame() {
        return frame(RpcConstants.HEARTBEAT_REQUEST_TYPE, new byte[0]);
    }

    private static ByteBuf frame(byte type, byte[] body) {
        ByteBuf frame = Unpooled.buffer(RpcConstants.HEAD_LENGTH + body.length);
        frame.writeBytes(RpcConstants.MAGIC_NUMBER);
        frame.writeByte(RpcConstants.VERSION);
        frame.writeInt(RpcConstants.HEAD_LENGTH + body.length);
        frame.writeByte(type);
        frame.writeByte(SerializationTypeEnum.HESSIAN.getCode());
        frame.writeByte(CompressTypeEnum.GZIP.getCode());
        frame.writeInt(1);
        frame.writeBytes(body);
        return frame;
    }
}
