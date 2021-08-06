/*
 * Copyright 2020 LINE Corporation
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
/*
 * Copyright 2012-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.spring;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.util.StringUtils;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Duration format styles.
 */
enum DurationStyle {

    // Forked from Spring Boot 2.3.1.
    // https://github.com/spring-projects/spring-boot/blob/b21c09020da7b237731d69c5c96b163e810c200f/spring-boot-project/spring-boot/src/main/java/org/springframework/boot/convert/DurationStyle.java

    /**
     * Simple formatting, for example '1s'.
     */
    SIMPLE("^([+-]?\\d+)([a-zA-Z]{0,2})$") {
        @Override
        Duration parse(String value, @Nullable ChronoUnit unit) {
            try {
                final Matcher matcher = matcher(value);
                checkState(matcher.matches(), "Does not match simple duration pattern");
                final String suffix = matcher.group(2);
                return (StringUtils.hasLength(suffix) ? Unit.fromSuffix(suffix) : Unit.fromChronoUnit(unit))
                        .parse(matcher.group(1));
            } catch (Exception ex) {
                throw new IllegalArgumentException("\'" + value + "' is not a valid simple duration", ex);
            }
        }
    },

    /**
     * ISO-8601 formatting.
     */
    ISO8601("^[+-]?P.*$") {
        @Override
        Duration parse(String value, @Nullable ChronoUnit unit) {
            try {
                return Duration.parse(value);
            } catch (Exception ex) {
                throw new IllegalArgumentException("\'" + value + "' is not a valid ISO-8601 duration", ex);
            }
        }
    };

    private final Pattern pattern;

    DurationStyle(String pattern) {
        this.pattern = Pattern.compile(pattern);
    }

    protected final boolean matches(String value) {
        return pattern.matcher(value).matches();
    }

    protected final Matcher matcher(String value) {
        return pattern.matcher(value);
    }

    /**
     * Parses the given value to a duration.
     * @param value the value to parse
     * @return a duration
     */
    Duration parse(String value) {
        return parse(value, null);
    }

    /**
     * Parses the given value to a duration.
     * @param value the value to parse
     * @param unit the duration unit to use if the value doesn't specify one ({@code null}
     * will default to ms)
     * @return a duration
     */
    abstract Duration parse(String value, @Nullable ChronoUnit unit);

    /**
     * Detects the style from the given source value.
     * @param value the source value
     * @return the duration style
     * @throws IllegalArgumentException if the value is not a known style
     */
    static DurationStyle detect(String value) {
        requireNonNull(value, "value");
        for (DurationStyle candidate : values()) {
            if (candidate.matches(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("\'" + value + "' is not a valid duration");
    }

    /**
     * Units that we support.
     */
    enum Unit {

        /**
         * Nanoseconds.
         */
        NANOS(ChronoUnit.NANOS, "ns", Duration::toNanos),

        /**
         * Microseconds.
         */
        MICROS(ChronoUnit.MICROS, "us", duration -> duration.toMillis() * 1000L),

        /**
         * Milliseconds.
         */
        MILLIS(ChronoUnit.MILLIS, "ms", Duration::toMillis),

        /**
         * Seconds.
         */
        SECONDS(ChronoUnit.SECONDS, "s", Duration::getSeconds),

        /**
         * Minutes.
         */
        MINUTES(ChronoUnit.MINUTES, "m", Duration::toMinutes),

        /**
         * Hours.
         */
        HOURS(ChronoUnit.HOURS, "h", Duration::toHours),

        /**
         * Days.
         */
        DAYS(ChronoUnit.DAYS, "d", Duration::toDays);

        private final ChronoUnit chronoUnit;

        private final String suffix;

        private Function<Duration, Long> longValue;

        Unit(ChronoUnit chronoUnit, String suffix, Function<Duration, Long> toUnit) {
            this.chronoUnit = chronoUnit;
            this.suffix = suffix;
            this.longValue = toUnit;
        }

        Duration parse(String value) {
            return Duration.of(Long.parseLong(value), chronoUnit);
        }

        static Unit fromChronoUnit(@Nullable ChronoUnit chronoUnit) {
            if (chronoUnit == null) {
                return MILLIS;
            }
            for (Unit candidate : values()) {
                if (candidate.chronoUnit == chronoUnit) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("Unknown unit " + chronoUnit);
        }

        static Unit fromSuffix(String suffix) {
            for (Unit candidate : values()) {
                if (candidate.suffix.equalsIgnoreCase(suffix)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("Unknown unit \'" + suffix + "\'");
        }
    }
}
