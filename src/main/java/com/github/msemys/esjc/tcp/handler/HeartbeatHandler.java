package com.github.msemys.esjc.tcp.handler;

import com.github.msemys.esjc.tcp.TcpCommand;
import com.github.msemys.esjc.tcp.TcpPackage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class HeartbeatHandler extends SimpleChannelInboundHandler<TcpPackage> {
    private static final Logger logger = LoggerFactory.getLogger(HeartbeatHandler.class);

    private final long timeoutMillis;
    private Map<UUID, ScheduledFuture<?>> timeoutTasks = new ConcurrentHashMap<>();

    public HeartbeatHandler(Duration timeout) {
        timeoutMillis = timeout.toMillis();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TcpPackage msg) throws Exception {
        switch (msg.command) {
            case HeartbeatRequestCommand:
                ctx.writeAndFlush(TcpPackage.newBuilder()
                    .command(TcpCommand.HeartbeatResponseCommand)
                    .correlationId(msg.correlationId)
                    .build());
                break;
            case HeartbeatResponseCommand:
                cancelTimeoutTask(msg.correlationId);
                break;
            default:
                ctx.fireChannelRead(msg);
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        cancelAllTimeoutTasks();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            UUID correlationId = UUID.randomUUID();
            ScheduledFuture<?> timeoutTask = ctx.executor().schedule(() -> {
                logger.info("Closing TCP connection [{}, L{}] due to HEARTBEAT TIMEOUT.", ctx.channel().remoteAddress(), ctx.channel().localAddress());
                ctx.close();
            }, timeoutMillis, MILLISECONDS);
            timeoutTasks.put(correlationId, timeoutTask);

            ctx.writeAndFlush(TcpPackage.newBuilder()
                .command(TcpCommand.HeartbeatRequestCommand)
                .correlationId(correlationId)
                .build());
        }
    }

    private void cancelTimeoutTask(UUID correlationId) {
        ScheduledFuture<?> timeoutTask = timeoutTasks.remove(correlationId);
        if (timeoutTask != null) {
            timeoutTask.cancel(true);
        }
    }

    private void cancelAllTimeoutTasks() {
        timeoutTasks.forEach((uuid, scheduledFuture) -> scheduledFuture.cancel(true));
    }

}
