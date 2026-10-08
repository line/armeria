/*
 * Copyright 2026 LINE Corporation
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
package com.linecorp.armeria.internal.spring;

import static com.linecorp.armeria.spring.InternalServiceId.ACTUATOR;
import static com.linecorp.armeria.spring.InternalServiceId.ALL;
import static com.linecorp.armeria.spring.InternalServiceId.DOCS;
import static com.linecorp.armeria.spring.InternalServiceId.HEALTH;
import static com.linecorp.armeria.spring.InternalServiceId.METRICS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.spring.ArmeriaSettings.InternalServiceProperties;
import com.linecorp.armeria.spring.InternalServiceId;

class InternalServiceIdUtilTest {

    @Test
    void defaultProperties() {
        assertThat(InternalServiceIdUtil.resolveServiceIds(new InternalServiceProperties()))
                .containsExactly(DOCS, HEALTH, METRICS, ACTUATOR);
    }

    @Test
    void includeOnly() {
        assertThat(resolve(ImmutableList.of(METRICS, HEALTH), null)).containsExactly(METRICS, HEALTH);
        assertThat(resolve(ImmutableList.of(ALL), ImmutableList.of()))
                .containsExactly(DOCS, HEALTH, METRICS, ACTUATOR);
        assertThat(resolve(null, null)).isEmpty();
        assertThat(resolve(ImmutableList.of(), null)).isEmpty();
    }

    @Test
    void excludeFromInclude() {
        assertThat(resolve(ImmutableList.of(DOCS, HEALTH, METRICS), ImmutableList.of(HEALTH)))
                .containsExactly(DOCS, METRICS);
        assertThat(resolve(ImmutableList.of(ALL), ImmutableList.of(HEALTH, ACTUATOR)))
                .containsExactly(DOCS, METRICS);
        assertThat(resolve(ImmutableList.of(DOCS), ImmutableList.of(HEALTH))).containsExactly(DOCS);
    }

    @Test
    void excludeAll() {
        assertThat(resolve(ImmutableList.of(ALL), ImmutableList.of(ALL))).isEmpty();
        assertThat(resolve(ImmutableList.of(DOCS, HEALTH), ImmutableList.of(ALL))).isEmpty();
    }

    private static List<InternalServiceId> resolve(@Nullable List<InternalServiceId> include,
                                                   @Nullable List<InternalServiceId> exclude) {
        final InternalServiceProperties properties = new InternalServiceProperties();
        properties.setInclude(include);
        properties.setExclude(exclude);
        return InternalServiceIdUtil.resolveServiceIds(properties);
    }
}
