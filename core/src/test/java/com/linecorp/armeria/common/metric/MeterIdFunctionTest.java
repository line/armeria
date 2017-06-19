/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.metric;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.google.common.collect.ImmutableList;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.prometheus.PrometheusMeterRegistry;
import io.micrometer.core.instrument.util.MeterId;

public class MeterIdFunctionTest {

    @Test
    public void testWithTags() {
        final MeterIdFunction f =
                (registry, log) -> new MeterId("requests_total", Tags.zip("region", "us-west"));

        assertThat(f.withTags(Tags.zip("zone", "1a", "host", "foo")).apply(null, null))
                .isEqualTo(new MeterId("requests_total",
                                       Tags.zip("region", "us-west", "zone", "1a", "host", "foo")));
    }

    @Test
    public void testWithUnzippedTags() {
        final MeterIdFunction f =
                (registry, log) -> new MeterId("requests_total", Tags.zip("region", "us-east"));

        assertThat(f.withTags("host", "bar").apply(null, null))
                .isEqualTo(new MeterId("requests_total",
                                       Tags.zip("region", "us-east", "host", "bar")));
    }

    @Test
    public void testAndThen() {
        final MeterIdFunction f = (registry, log) -> new MeterId("foo", ImmutableList.of());
        final MeterIdFunction f2 = f.andThen(
                (registry, id) -> new MeterId(MeterRegistryUtil.name(registry, id.getName(), "bar"),
                                              id.getTags()));
        assertThat(f2.apply(new PrometheusMeterRegistry(), null))
                .isEqualTo(new MeterId("foo_bar", ImmutableList.of()));
    }
}
