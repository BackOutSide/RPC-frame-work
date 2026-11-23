package io.github.backoutside.rivet.remoting.transport.netty.server;

/** Test-only access to the production server pipeline's package-private constructor. */
public final class NettyTracingTestHandlers {
    private NettyTracingTestHandlers() {
    }

    public static NettyRpcServerHandler withAdmission(RpcAdmissionHandler admission) {
        return new NettyRpcServerHandler(admission);
    }
}
