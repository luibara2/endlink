package org.endstone.proxy.network;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;

public final class LoggingExceptionHandler extends ChannelDuplexHandler {
    private final String side;

    public LoggingExceptionHandler(String side) {
        this.side = side;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.printf(
                "%s pipeline exception on %s -> %s:%n",
                side,
                ctx.channel().localAddress(),
                ctx.channel().remoteAddress()
        );
        cause.printStackTrace(System.err);
        ctx.close();
    }
}
