/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client.logging;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.pool.KeyedChannelPoolHandler;
import com.linecorp.armeria.client.pool.PoolKey;
import com.linecorp.armeria.common.util.TextFormatter;
import com.linecorp.armeria.common.util.Ticker;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

/**
 * Decorates a {@link KeyedChannelPoolHandler} to log the connection pool events.
 */
public class KeyedChannelPoolLoggingHandler implements KeyedChannelPoolHandler<PoolKey> {
    private static final Logger logger = LoggerFactory.getLogger(KeyedChannelPoolLoggingHandler.class);

    private static final AttributeKey<ChannelStat> STAT =
            AttributeKey.valueOf(KeyedChannelPoolLoggingHandler.class, "STAT");

    private final AtomicInteger activeChannels = new AtomicInteger();
    private final Ticker ticker;

    private enum EventType {
        CREATED("Created on"),
        ACQUIRED("Acquired from"),
        RELEASED("Released from"),
        CLOSED("Closed on");

        private final String actionDescription;

        EventType(String actionDescription) {

            this.actionDescription = actionDescription;
        }

        String actionDescription() {
            return actionDescription;
        }
    }

    private static class ChannelStat extends AtomicInteger {
        private static final long serialVersionUID = -851703990840809289L;
        private final Ticker ticker;
        private final long createdNanos;
        private long lastUsedNanos;

        ChannelStat(Ticker ticker) {
            createdNanos = ticker.read();
            this.ticker = ticker;
        }

        @SuppressWarnings("checkstyle:fallthrough")
        void collect(EventType eventType) {
            switch (eventType) {
            case ACQUIRED:
                incrementAndGet();
            case RELEASED:
                lastUsedNanos = System.nanoTime();
            }
        }

        @SuppressWarnings("checkstyle:fallthrough")
        StringBuilder status(EventType eventType, StringBuilder buf) {
            long currentNanos = ticker.read();
            switch (eventType) {
            case ACQUIRED:
                buf.append("was idle for ");
                TextFormatter.appendElapsed(buf, lastUsedNanos, currentNanos);
                buf.append(", ");
            case RELEASED:
            case CLOSED:
                buf.append("used ").append(get()).append(" time(s), ");
                TextFormatter.appendElapsed(buf, createdNanos, currentNanos);
                buf.append(" old");
            }
            return buf;
        }

        StringBuilder collectAndStatus(EventType eventType, StringBuilder buf) {
            collect(eventType);
            return status(eventType, buf);
        }
    }

    /**
     * Creates a new instance with a {@linkplain Ticker#systemTicker() system ticker}.
     */
    public KeyedChannelPoolLoggingHandler() {
        this(Ticker.systemTicker());
    }

    /**
     * Creates a new instance with an alternative {@link Ticker}.
     *
     * @param ticker an alternative {@link Ticker}
     */
    public KeyedChannelPoolLoggingHandler(Ticker ticker) {
        this.ticker = requireNonNull(ticker, "ticker");
    }

    @Override
    public void channelReleased(PoolKey key, Channel ch) throws Exception {
        logInfo(key, ch, EventType.RELEASED);
    }

    @Override
    public void channelAcquired(PoolKey key, Channel ch) throws Exception {
        logInfo(key, ch, EventType.ACQUIRED);
    }

    @Override
    public void channelCreated(PoolKey key, Channel ch) throws Exception {
        logInfo(key, ch, EventType.CREATED);
    }

    @Override
    public void channelClosed(PoolKey key, Channel ch) throws Exception {
        logInfo(key, ch, EventType.CLOSED);
    }

    private void logInfo(PoolKey key, Channel ch, EventType eventType) {
        if (logger.isInfoEnabled()) {
            logger.info("{} {} {} ({})", ch, eventType.actionDescription(), key, status(ch, eventType));
        }
    }

    private StringBuilder status(Channel ch, EventType event) {
        final ChannelStat state;
        if (event == EventType.CREATED) {
            state = new ChannelStat(ticker);
            ch.attr(STAT).set(state);
        } else {
            state = ch.attr(STAT).get();
        }

        final StringBuilder buf = new StringBuilder(16);
        if (state != null) {
            state.collectAndStatus(event, buf);
            activeChannelStatus(event, buf);
        }
        return buf;
    }

    @SuppressWarnings("checkstyle:fallthrough")
    private void activeChannelStatus(EventType event, StringBuilder buf) {
        switch (event) {
        case CREATED:
            activeChannels.incrementAndGet();
            break;
        case CLOSED:
            activeChannels.decrementAndGet();
        default:
            buf.append(", ");
        }
        buf.append("active channels: ").append(activeChannels.get());
    }
}
