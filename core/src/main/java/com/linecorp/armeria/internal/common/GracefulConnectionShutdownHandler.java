package com.linecorp.armeria.internal.common;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import io.netty.channel.ChannelHandlerContext;

public abstract class GracefulConnectionShutdownHandler {

    boolean gracePeriodStarted;
    @Nullable
    private ScheduledFuture<?> future;

    /**
     * Code executed on grace period start. Guaranteed to be executed at most once.
     */
    public abstract void onGracePeriodStart(ChannelHandlerContext ctx);
    /**
     * Code executed on grace period end.
     */
    public abstract void onGracePeriodEnd(ChannelHandlerContext ctx);

    public void setup(ChannelHandlerContext ctx, Duration gracePeriod) {
        if (future != null &&
            future.getDelay(TimeUnit.NANOSECONDS) > gracePeriod.toNanos()) {
            future.cancel(false);
        }
        if (gracePeriod.compareTo(Duration.ZERO) > 0) {
            if (!gracePeriodStarted) {
                onGracePeriodStart(ctx);
                gracePeriodStarted = true;
            }
            future = ctx.executor().schedule(() -> onGracePeriodEnd(ctx),
                                             gracePeriod.toNanos(), TimeUnit.NANOSECONDS);
        } else {
            onGracePeriodEnd(ctx);
        }
    }

    public void cancel() {
        if (future != null) {
            future.cancel(false);
            future = null;
        }
    }
}
