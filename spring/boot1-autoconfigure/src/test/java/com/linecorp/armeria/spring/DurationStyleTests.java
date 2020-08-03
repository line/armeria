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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DurationStyle}.
 */
class DurationStyleTests {

    // Forked from Spring Boot 2.3.1.
    // https://github.com/spring-projects/spring-boot/blob/b21c09020d/spring-boot-project/spring-boot/src/test/java/org/springframework/boot/convert/DurationStyleTests.java

    @Test
    void detectWhenSimpleShouldReturnSimple() {
        assertThat(DurationStyle.detect("10")).isEqualTo(DurationStyle.SIMPLE);
        assertThat(DurationStyle.detect("+10")).isEqualTo(DurationStyle.SIMPLE);
        assertThat(DurationStyle.detect("-10")).isEqualTo(DurationStyle.SIMPLE);
        assertThat(DurationStyle.detect("10ns")).isEqualTo(DurationStyle.SIMPLE);
        assertThat(DurationStyle.detect("10ms")).isEqualTo(DurationStyle.SIMPLE);
        assertThat(DurationStyle.detect("10s")).isEqualTo(DurationStyle.SIMPLE);
        assertThat(DurationStyle.detect("10m")).isEqualTo(DurationStyle.SIMPLE);
        assertThat(DurationStyle.detect("10h")).isEqualTo(DurationStyle.SIMPLE);
        assertThat(DurationStyle.detect("10d")).isEqualTo(DurationStyle.SIMPLE);
        assertThat(DurationStyle.detect("-10ms")).isEqualTo(DurationStyle.SIMPLE);
        assertThat(DurationStyle.detect("-10ms")).isEqualTo(DurationStyle.SIMPLE);
        assertThat(DurationStyle.detect("10D")).isEqualTo(DurationStyle.SIMPLE);
    }

    @Test
    void detectWhenIso8601ShouldReturnIso8601() {
        assertThat(DurationStyle.detect("PT20.345S")).isEqualTo(DurationStyle.ISO8601);
        assertThat(DurationStyle.detect("PT15M")).isEqualTo(DurationStyle.ISO8601);
        assertThat(DurationStyle.detect("+PT15M")).isEqualTo(DurationStyle.ISO8601);
        assertThat(DurationStyle.detect("PT10H")).isEqualTo(DurationStyle.ISO8601);
        assertThat(DurationStyle.detect("P2D")).isEqualTo(DurationStyle.ISO8601);
        assertThat(DurationStyle.detect("P2DT3H4M")).isEqualTo(DurationStyle.ISO8601);
        assertThat(DurationStyle.detect("-PT6H3M")).isEqualTo(DurationStyle.ISO8601);
        assertThat(DurationStyle.detect("-PT-6H+3M")).isEqualTo(DurationStyle.ISO8601);
    }

    @Test
    void detectWhenUnknownShouldThrowException() {
        assertThatIllegalArgumentException().isThrownBy(() -> DurationStyle.detect("bad"))
                                            .withMessageContaining("'bad' is not a valid duration");
    }

    @Test
    void parseIso8601ShouldParse() {
        assertThat(DurationStyle.ISO8601.parse("PT20.345S")).isEqualTo(Duration.parse("PT20.345S"));
        assertThat(DurationStyle.ISO8601.parse("PT15M")).isEqualTo(Duration.parse("PT15M"));
        assertThat(DurationStyle.ISO8601.parse("+PT15M")).isEqualTo(Duration.parse("PT15M"));
        assertThat(DurationStyle.ISO8601.parse("PT10H")).isEqualTo(Duration.parse("PT10H"));
        assertThat(DurationStyle.ISO8601.parse("P2D")).isEqualTo(Duration.parse("P2D"));
        assertThat(DurationStyle.ISO8601.parse("P2DT3H4M")).isEqualTo(Duration.parse("P2DT3H4M"));
        assertThat(DurationStyle.ISO8601.parse("-PT6H3M")).isEqualTo(Duration.parse("-PT6H3M"));
        assertThat(DurationStyle.ISO8601.parse("-PT-6H+3M")).isEqualTo(Duration.parse("-PT-6H+3M"));
    }

    @Test
    void parseIso8601WithUnitShouldIgnoreUnit() {
        assertThat(DurationStyle.ISO8601.parse("PT20.345S", ChronoUnit.SECONDS)).isEqualTo(
                Duration.parse("PT20.345S"));
        assertThat(DurationStyle.ISO8601.parse("PT15M", ChronoUnit.SECONDS)).isEqualTo(Duration.parse("PT15M"));
        assertThat(DurationStyle.ISO8601.parse("+PT15M", ChronoUnit.SECONDS)).isEqualTo(
                Duration.parse("PT15M"));
        assertThat(DurationStyle.ISO8601.parse("PT10H", ChronoUnit.SECONDS)).isEqualTo(Duration.parse("PT10H"));
        assertThat(DurationStyle.ISO8601.parse("P2D")).isEqualTo(Duration.parse("P2D"));
        assertThat(DurationStyle.ISO8601.parse("P2DT3H4M", ChronoUnit.SECONDS)).isEqualTo(
                Duration.parse("P2DT3H4M"));
        assertThat(DurationStyle.ISO8601.parse("-PT6H3M", ChronoUnit.SECONDS)).isEqualTo(
                Duration.parse("-PT6H3M"));
        assertThat(DurationStyle.ISO8601.parse("-PT-6H+3M", ChronoUnit.SECONDS)).isEqualTo(
                Duration.parse("-PT-6H+3M"));
    }

    @Test
    void parseIso8601WhenSimpleShouldThrowException() {
        assertThatIllegalArgumentException().isThrownBy(() -> DurationStyle.ISO8601.parse("10d"))
                                            .withMessageContaining("'10d' is not a valid ISO-8601 duration");
    }

    @Test
    void parseSimpleShouldParse() {
        assertThat(DurationStyle.SIMPLE.parse("10m")).hasMinutes(10);
    }

    @Test
    void parseSimpleWithUnitShouldUseUnitAsFallback() {
        assertThat(DurationStyle.SIMPLE.parse("10m", ChronoUnit.SECONDS)).hasMinutes(10);
        assertThat(DurationStyle.SIMPLE.parse("10", ChronoUnit.MINUTES)).hasMinutes(10);
    }

    @Test
    void parseSimpleWhenUnknownUnitShouldThrowException() {
        assertThatIllegalArgumentException().isThrownBy(() -> DurationStyle.SIMPLE.parse("10mb"))
                                            .satisfies(ex -> assertThat(ex.getCause().getMessage())
                                                    .isEqualTo("Unknown unit 'mb'"));
    }

    @Test
    void parseSimpleWhenIso8601ShouldThrowException() {
        assertThatIllegalArgumentException().isThrownBy(() -> DurationStyle.SIMPLE.parse("PT10H"))
                                            .withMessageContaining("'PT10H' is not a valid simple duration");
    }
}
