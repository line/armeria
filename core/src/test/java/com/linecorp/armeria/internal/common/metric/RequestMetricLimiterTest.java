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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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
    void acceptActiveLimitAndWarning() {
        final RequestMetricLimiter limiter = new RequestMetricLimiter(2);
        final MeterIdPrefix prefix1 = new MeterIdPrefix("test.active", "route", "1");
        final MeterIdPrefix prefix2 = new MeterIdPrefix("test.active", "route", "2");
        final MeterIdPrefix prefix3 = new MeterIdPrefix("test.active", "route", "3");

        assertThat(limiter.acceptActive(prefix1)).isTrue();
        assertThat(limiter.acceptActive(prefix2)).isTrue();
        assertThat(limiter.seenActivePrefixes()).containsExactlyInAnyOrder(prefix1, prefix2);
        assertThat(logAppender.list).isEmpty();

        // Previously seen prefixes are accepted even after reaching the limit
        assertThat(limiter.acceptActive(prefix1)).isTrue();
        assertThat(limiter.acceptActive(prefix2)).isTrue();
        assertThat(logAppender.list).isEmpty();

        // New prefix is rejected and warning is emitted
        assertThat(limiter.acceptActive(prefix3)).isFalse();
        assertThat(logAppender.list).hasSize(1);
        assertThat(logAppender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(logAppender.list.get(0).getFormattedMessage())
                .contains("The number of distinct active request metrics for 'test.active' has exceeded")
                .contains("maximum cardinality (2)");

        // Subsequent rejections do not log duplicate warnings
        assertThat(limiter.acceptActive(new MeterIdPrefix("test.active", "route", "4"))).isFalse();
        assertThat(logAppender.list).hasSize(1);

        // Seen prefixes still accepted
        assertThat(limiter.acceptActive(prefix1)).isTrue();
    }

    @Test
    void acceptCompleteLimitAndWarning() {
        final RequestMetricLimiter limiter = new RequestMetricLimiter(2);
        final MeterIdPrefix prefix1 = new MeterIdPrefix("test.complete", "route", "1");
        final MeterIdPrefix prefix2 = new MeterIdPrefix("test.complete", "route", "2");
        final MeterIdPrefix prefix3 = new MeterIdPrefix("test.complete", "route", "3");

        assertThat(limiter.acceptComplete(prefix1)).isTrue();
        assertThat(limiter.acceptComplete(prefix2)).isTrue();
        assertThat(limiter.seenCompletePrefixes()).containsExactlyInAnyOrder(prefix1, prefix2);
        assertThat(logAppender.list).isEmpty();

        // Previously seen prefixes are accepted
        assertThat(limiter.acceptComplete(prefix1)).isTrue();
        assertThat(logAppender.list).isEmpty();

        // New prefix is rejected and warning is emitted
        assertThat(limiter.acceptComplete(prefix3)).isFalse();
        assertThat(logAppender.list).hasSize(1);
        assertThat(logAppender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(logAppender.list.get(0).getFormattedMessage())
                .contains("The number of distinct request metrics for 'test.complete' has exceeded")
                .contains("maximum cardinality (2)");

        // Subsequent rejections do not log duplicate warnings
        assertThat(limiter.acceptComplete(new MeterIdPrefix("test.complete", "route", "4"))).isFalse();
        assertThat(logAppender.list).hasSize(1);

        // Seen prefixes still accepted
        assertThat(limiter.acceptComplete(prefix2)).isTrue();
    }
}
