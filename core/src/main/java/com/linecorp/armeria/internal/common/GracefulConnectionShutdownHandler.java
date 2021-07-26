package com.linecorp.armeria.internal.common;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/**
 * Abstract class that's used implement protocol-specific graceful connection shutdown logic.
 */
public abstract class GracefulConnectionShutdownHandler {
    private static final Logger logger = LoggerFactory.getLogger(GracefulConnectionShutdownHandler.class);

    @Nullable
    ChannelPromise promise;
    Duration gracePeriod = Duration.ZERO;
    boolean started;
    @Nullable
    private ScheduledFuture<?> gracePeriodFuture;

    /**
     * Code executed on grace period start. Executed at most once.
     */
    public abstract void onGracePeriodStart(ChannelHandlerContext ctx);
    /**
     * Code executed on grace period end. Executed at most once.
     */
    public abstract void onGracePeriodEnd(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception;

    public void start(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        if (this.promise == null) {
            this.promise = promise;
        } else {
            // Chain promises in case start is called multiple times.
            this.promise.addListener((ChannelFutureListener) future -> {
                if (future.cause() != null) {
                    promise.setFailure(future.cause());
                } else {
                    promise.setSuccess();
                }
            });
        }
        if (gracePeriodFuture != null) {
            if (gracePeriodFuture.getDelay(TimeUnit.NANOSECONDS) > gracePeriod.toNanos()) {
                // Maybe reschedule below.
                gracePeriodFuture.cancel(false);
                gracePeriodFuture = null;
            } else {
                // Grace period is already scheduled to finish earlier.
                return;
            }
        }
        if (gracePeriod.compareTo(Duration.ZERO) > 0) {
            if (!started) {
                onGracePeriodStart(ctx);
            }
            gracePeriodFuture = ctx.executor().schedule(() -> finish(ctx, this.promise),
                                                        gracePeriod.toNanos(), TimeUnit.NANOSECONDS);
        } else {
            finish(ctx, this.promise);
        }
        started = true;
    }

    private void finish(ChannelHandlerContext ctx, ChannelPromise promise) {
        try {
            onGracePeriodEnd(ctx, promise);
        } catch (Exception e) {
            logger.warn("Unexpected exception:", e);
        }
    }

    public void cancel() {
        if (gracePeriodFuture != null) {
            gracePeriodFuture.cancel(false);
            gracePeriodFuture = null;
        }
    }

    public void updateGracePeriod(@Nullable Duration gracePeriod) {
        if (gracePeriod == null){
            return;
        }
        if (gracePeriod.compareTo(Duration.ZERO) > 0) {
            this.gracePeriod = gracePeriod;
        } else {
            this.gracePeriod = Duration.ZERO;
        }
    }
}
