/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.internal.common.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Ticker;

import com.linecorp.armeria.common.metric.MeterIdPrefix;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class RequestMetricLimiterTest {

    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestMetricLimiter.class);

    @BeforeEach
    void attachAppender() {
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(logAppender);
        logAppender.list.clear();
    }

    @Test
    void invalidMaxCardinality() {
        assertThatThrownBy(() -> new RequestMetricLimiter(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestMetricLimiter(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidIdleTimeout() {
        assertThatThrownBy(() -> new RequestMetricLimiter(1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestMetricLimiter(1, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestMetricLimiter(1, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void acceptLimitAndWarning() {
        final RequestMetricLimiter limiter = new RequestMetricLimiter(2);
        final MeterIdPrefix prefix1 = new MeterIdPrefix("test.active", "route", "1");
        final MeterIdPrefix prefix2 = new MeterIdPrefix("test.active", "route", "2");
        final MeterIdPrefix prefix3 = new MeterIdPrefix("test.active", "route", "3");

        assertThat(limiter.accept(prefix1)).isTrue();
        assertThat(limiter.accept(prefix2)).isTrue();
        assertThat(limiter.seenPrefixes()).containsExactlyInAnyOrder(prefix1, prefix2);
        assertThat(logAppender.list).isEmpty();

        // Previously seen prefixes are accepted even after reaching the limit
        assertThat(limiter.accept(prefix1)).isTrue();
        assertThat(limiter.accept(prefix2)).isTrue();
        assertThat(logAppender.list).isEmpty();

        // New prefix is rejected and warning is emitted
        assertThat(limiter.accept(prefix3)).isFalse();
        assertThat(logAppender.list).hasSize(1);
        assertThat(logAppender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(logAppender.list.get(0).getFormattedMessage())
                .contains("The number of distinct active request metrics for 'test.active' has exceeded")
                .contains("maximum cardinality (2)");

        // Subsequent rejections do not log duplicate warnings
        assertThat(limiter.accept(new MeterIdPrefix("test.active", "route", "4"))).isFalse();
        assertThat(logAppender.list).hasSize(1);

        // Seen prefixes still accepted
        assertThat(limiter.accept(prefix1)).isTrue();
    }

    @Test
    void idleTimeoutEviction() {
        final AtomicLong nanos = new AtomicLong();
        final Ticker ticker = nanos::get;
        final Duration timeout = Duration.ofSeconds(10);
        final RequestMetricLimiter limiter = new RequestMetricLimiter(1, timeout, ticker);

        final MeterIdPrefix prefix1 = new MeterIdPrefix("test.active", "route", "1");
        final MeterIdPrefix prefix2 = new MeterIdPrefix("test.active", "route", "2");

        assertThat(limiter.accept(prefix1)).isTrue();
        assertThat(limiter.accept(prefix2)).isFalse();

        // Advance ticker past idle timeout (11 seconds)
        nanos.addAndGet(Duration.ofSeconds(11).toNanos());

        // Now prefix2 is accepted because prefix1 expired due to inactivity
        assertThat(limiter.accept(prefix2)).isTrue();
        assertThat(limiter.seenPrefixes()).containsExactly(prefix2);
    }

    @Test
    void idleTimeoutRenewedOnAccess() {
        final AtomicLong nanos = new AtomicLong();
        final Ticker ticker = nanos::get;
        final Duration timeout = Duration.ofSeconds(10);
        final RequestMetricLimiter limiter = new RequestMetricLimiter(1, timeout, ticker);

        final MeterIdPrefix prefix1 = new MeterIdPrefix("test.active", "route", "1");
        final MeterIdPrefix prefix2 = new MeterIdPrefix("test.active", "route", "2");

        assertThat(limiter.accept(prefix1)).isTrue();

        // Advance 6 seconds (not expired yet)
        nanos.addAndGet(Duration.ofSeconds(6).toNanos());
        // Access prefix1, which should reset the idle timeout
        assertThat(limiter.accept(prefix1)).isTrue();

        // Advance another 6 seconds (total 12s from start, but only 6s from last access)
        nanos.addAndGet(Duration.ofSeconds(6).toNanos());

        // prefix2 is still rejected because prefix1 has not been idle for 10s
        assertThat(limiter.accept(prefix2)).isFalse();

        // Advance 11 seconds without accessing prefix1
        nanos.addAndGet(Duration.ofSeconds(11).toNanos());

        // Now prefix2 is accepted
        assertThat(limiter.accept(prefix2)).isTrue();
    }
}
