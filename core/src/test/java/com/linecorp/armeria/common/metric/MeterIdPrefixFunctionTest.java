/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.common.metric;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.google.common.collect.ImmutableList;

public class MeterIdPrefixFunctionTest {

    @Test
    public void testWithTags() {
        final MeterIdPrefixFunction f =
                (registry, log) -> new MeterIdPrefix("requests_total", "region", "us-west");

        assertThat(f.withTags("zone", "1a", "host", "foo").apply(null, null))
                .isEqualTo(new MeterIdPrefix("requests_total",
                                             "region", "us-west", "zone", "1a", "host", "foo"));
    }

    @Test
    public void testWithUnzippedTags() {
        final MeterIdPrefixFunction f =
                (registry, log) -> new MeterIdPrefix("requests_total", "region", "us-east");

        assertThat(f.withTags("host", "bar").apply(null, null))
                .isEqualTo(new MeterIdPrefix("requests_total", "region", "us-east", "host", "bar"));
    }

    @Test
    public void testAndThen() {
        final MeterIdPrefixFunction f = (registry, log) -> new MeterIdPrefix("foo", ImmutableList.of());
        final MeterIdPrefixFunction f2 = f.andThen((registry, id) -> id.append("bar"));
        assertThat(f2.apply(PrometheusMeterRegistries.newRegistry(), null))
                .isEqualTo(new MeterIdPrefix("foo.bar", ImmutableList.of()));
    }
}
