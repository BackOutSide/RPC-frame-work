package io.github.backoutside.rivet.remoting.transport.netty.codec;

import io.github.backoutside.rivet.compress.Compress;
import io.github.backoutside.rivet.enums.CompressTypeEnum;
import io.github.backoutside.rivet.enums.RpcStatusCode;
import io.github.backoutside.rivet.enums.SerializationTypeEnum;
import io.github.backoutside.rivet.exception.RpcException;
import io.github.backoutside.rivet.extension.ExtensionLoader;
import io.github.backoutside.rivet.remoting.constants.RpcConstants;
import io.github.backoutside.rivet.remoting.dto.RpcMessage;
import io.github.backoutside.rivet.remoting.dto.RpcRequest;
import io.github.backoutside.rivet.remoting.dto.RpcResponse;
import io.github.backoutside.rivet.serialize.Serializer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Encodes and decodes the current 16-byte RPC header and its optional body. */
@ChannelHandler.Sharable
public class RpcMessageCodec extends MessageToMessageCodec<ByteBuf, RpcMessage> {
    private static final AtomicInteger NEXT_REQUEST_ID = new AtomicInteger();

    @Override
    protected void encode(ChannelHandlerContext ctx, RpcMessage message, List<Object> output) {
        byte type = message.getMessageType();
        checkMessageType(type);
        checkCodecSelection(message.getCodec(), message.getCompress());

        ByteBuf frame = ctx.alloc().buffer();
        boolean handedOff = false;
        try {
            frame.writeBytes(RpcConstants.MAGIC_NUMBER);
            frame.writeByte(RpcConstants.VERSION);
            frame.writeInt(0);
            frame.writeByte(type);
            frame.writeByte(message.getCodec());
            frame.writeByte(message.getCompress());
            frame.writeInt(NEXT_REQUEST_ID.getAndIncrement());

            if (!isHeartbeat(type)) {
                if (message.getData() == null) {
                    throw protocolError("RPC message body is missing", null);
                }
                byte[] body;
                try {
                    body = serializer(message.getCodec()).serialize(message.getData());
                    if (body == null) {
                        throw new IllegalStateException("Serializer returned no bytes");
                    }
                    body = compressor(message.getCompress()).compress(body);
                    if (body == null) {
                        throw new IllegalStateException("Compressor returned no bytes");
                    }
                } catch (RuntimeException cause) {
                    throw codecError("Could not encode RPC body", cause);
                }
                if (body.length > RpcConstants.MAX_FRAME_LENGTH - RpcConstants.HEAD_LENGTH) {
                    throw protocolError("RPC frame exceeds maximum length", null);
                }
                frame.writeBytes(body);
            }

            frame.setInt(RpcConstants.MAGIC_NUMBER.length + 1, frame.readableBytes());
            output.add(frame);
            handedOff = true;
        } finally {
            if (!handedOff) {
                frame.release();
            }
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> output) {
        int actualLength = frame.readableBytes();
        if (actualLength < RpcConstants.HEAD_LENGTH || actualLength > RpcConstants.MAX_FRAME_LENGTH) {
            throw protocolError("Invalid RPC frame length", null);
        }
        checkMagicNumber(frame);
        checkVersion(frame);
        int declaredLength = frame.readInt();
        if (declaredLength != actualLength) {
            throw protocolError("RPC frame length does not match header", null);
        }

        byte type = frame.readByte();
        checkMessageType(type);
        byte codec = frame.readByte();
        byte compress = frame.readByte();
        checkCodecSelection(codec, compress);
        int requestId = frame.readInt();

        RpcMessage message = RpcMessage.builder()
                .messageType(type).codec(codec).compress(compress).requestId(requestId).build();
        if (isHeartbeat(type)) {
            if (declaredLength != RpcConstants.HEAD_LENGTH) {
                throw protocolError("RPC heartbeat must not have a body", null);
            }
            message.setData(type == RpcConstants.HEARTBEAT_REQUEST_TYPE ? RpcConstants.PING : RpcConstants.PONG);
            output.add(message);
            return;
        }

        int bodyLength = declaredLength - RpcConstants.HEAD_LENGTH;
        if (bodyLength == 0) {
            throw protocolError("RPC message body is missing", null);
        }
        byte[] body = new byte[bodyLength];
        frame.readBytes(body);
        try {
            body = compressor(compress).decompress(body);
            if (body == null) {
                throw new IllegalStateException("Decompressor returned no bytes");
            }
            Object data = type == RpcConstants.REQUEST_TYPE
                    ? serializer(codec).deserialize(body, RpcRequest.class)
                    : serializer(codec).deserialize(body, RpcResponse.class);
            if (data == null) {
                throw new IllegalStateException("Deserializer returned no object");
            }
            message.setData(data);
        } catch (RuntimeException cause) {
            throw codecError("Could not decode RPC body", cause);
        }
        output.add(message);
    }

    private static boolean isHeartbeat(byte type) {
        return type == RpcConstants.HEARTBEAT_REQUEST_TYPE || type == RpcConstants.HEARTBEAT_RESPONSE_TYPE;
    }

    private static void checkMessageType(byte type) {
        if (type != RpcConstants.REQUEST_TYPE && type != RpcConstants.RESPONSE_TYPE && !isHeartbeat(type)) {
            throw protocolError("Unknown RPC message type", null);
        }
    }

    private static void checkCodecSelection(byte codec, byte compress) {
        if (SerializationTypeEnum.getName(codec) == null) {
            throw codecError("Unknown RPC serializer", null);
        }
        if (CompressTypeEnum.getName(compress) == null) {
            throw codecError("Unknown RPC compression type", null);
        }
    }

    private static Serializer serializer(byte code) {
        return ExtensionLoader.getExtensionLoader(Serializer.class)
                .getExtension(SerializationTypeEnum.getName(code));
    }

    private static Compress compressor(byte code) {
        return ExtensionLoader.getExtensionLoader(Compress.class)
                .getExtension(CompressTypeEnum.getName(code));
    }

    private static void checkVersion(ByteBuf frame) {
        if (frame.readByte() != RpcConstants.VERSION) {
            throw protocolError("Unsupported RPC protocol version", null);
        }
    }

    private static void checkMagicNumber(ByteBuf frame) {
        for (byte expected : RpcConstants.MAGIC_NUMBER) {
            if (frame.readByte() != expected) {
                throw protocolError("Invalid RPC magic number", null);
            }
        }
    }

    private static RpcException protocolError(String message, Throwable cause) {
        return new RpcException(RpcStatusCode.PROTOCOL_ERROR, null, message, cause);
    }

    private static RpcException codecError(String message, Throwable cause) {
        return new RpcException(RpcStatusCode.CODEC_ERROR, null, message, cause);
    }
}
