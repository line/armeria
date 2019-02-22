/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.Test;

public class ClientCacheControlTest {

    @Test
    public void testConstants() {
        assertThat(ClientCacheControl.EMPTY.isEmpty()).isTrue();
        assertThat(ClientCacheControl.FORCE_CACHE.asHeaderValue())
                .isEqualTo("only-if-cached, max-stale=2147483647");
        assertThat(ClientCacheControl.FORCE_NETWORK.asHeaderValue())
                .isEqualTo("no-cache");
    }

    @Test
    public void testIsEmpty() {
        final ClientCacheControl cc = new ClientCacheControlBuilder().build();
        assertThat(cc.isEmpty()).isTrue();
        assertThat(cc.noCache()).isFalse();
        assertThat(cc.noStore()).isFalse();
        assertThat(cc.noTransform()).isFalse();
        assertThat(cc.maxAgeSeconds()).isEqualTo(-1);
        assertThat(cc.onlyIfCached()).isFalse();
        assertThat(cc.hasMaxStale()).isFalse();
        assertThat(cc.maxStaleSeconds()).isEqualTo(-1);
        assertThat(cc.minFreshSeconds()).isEqualTo(-1);
        assertThat(cc.staleWhileRevalidateSeconds()).isEqualTo(-1);
        assertThat(cc.staleIfErrorSeconds()).isEqualTo(-1);
        assertThat(cc.asHeaderValue()).isEmpty();
        assertThat(cc.toString()).isEqualTo("ClientCacheControl(<empty>)");
    }

    @Test
    public void testOnlyIfCached() {
        final ClientCacheControl cc = new ClientCacheControlBuilder().onlyIfCached().build();
        assertThat(cc.onlyIfCached()).isTrue();
        assertThat(cc.hasMaxStale()).isFalse();
        assertThat(cc.maxStaleSeconds()).isEqualTo(-1);
        assertThat(cc.minFreshSeconds()).isEqualTo(-1);
        assertThat(cc.staleWhileRevalidateSeconds()).isEqualTo(-1);
        assertThat(cc.staleIfErrorSeconds()).isEqualTo(-1);
        assertThat(cc.asHeaderValue()).isEqualTo("only-if-cached");
        assertThat(cc.toString()).isEqualTo("ClientCacheControl(only-if-cached)");
    }

    @Test
    public void testMaxStale() {
        final ClientCacheControl cc = new ClientCacheControlBuilder().maxStale().build();
        assertThat(cc.onlyIfCached()).isFalse();
        assertThat(cc.hasMaxStale()).isTrue();
        assertThat(cc.maxStaleSeconds()).isEqualTo(-1);
        assertThat(cc.minFreshSeconds()).isEqualTo(-1);
        assertThat(cc.staleWhileRevalidateSeconds()).isEqualTo(-1);
        assertThat(cc.staleIfErrorSeconds()).isEqualTo(-1);
        assertThat(cc.asHeaderValue()).isEqualTo("max-stale");
        assertThat(cc.toString()).isEqualTo("ClientCacheControl(max-stale)");
    }

    @Test
    public void testMaxStaleWithValue() {
        final ClientCacheControl cc = new ClientCacheControlBuilder().maxStale(Duration.ofHours(1)).build();
        assertThat(cc.onlyIfCached()).isFalse();
        assertThat(cc.hasMaxStale()).isTrue();
        assertThat(cc.maxStaleSeconds()).isEqualTo(3600);
        assertThat(cc.minFreshSeconds()).isEqualTo(-1);
        assertThat(cc.staleWhileRevalidateSeconds()).isEqualTo(-1);
        assertThat(cc.staleIfErrorSeconds()).isEqualTo(-1);
        assertThat(cc.asHeaderValue()).isEqualTo("max-stale=3600");
        assertThat(cc.toString()).isEqualTo("ClientCacheControl(max-stale=3600)");

        assertThat(new ClientCacheControlBuilder().maxStaleSeconds(3600).build()).isEqualTo(cc);
    }

    @Test
    public void testMinFresh() {
        final ClientCacheControl cc = new ClientCacheControlBuilder().minFresh(Duration.ofHours(1)).build();
        assertThat(cc.onlyIfCached()).isFalse();
        assertThat(cc.hasMaxStale()).isFalse();
        assertThat(cc.maxStaleSeconds()).isEqualTo(-1);
        assertThat(cc.minFreshSeconds()).isEqualTo(3600);
        assertThat(cc.staleWhileRevalidateSeconds()).isEqualTo(-1);
        assertThat(cc.staleIfErrorSeconds()).isEqualTo(-1);
        assertThat(cc.asHeaderValue()).isEqualTo("min-fresh=3600");
        assertThat(cc.toString()).isEqualTo("ClientCacheControl(min-fresh=3600)");

        assertThat(new ClientCacheControlBuilder().minFreshSeconds(3600).build()).isEqualTo(cc);
    }

    @Test
    public void testStaleWhileRevalidate() {
        final ClientCacheControl cc = new ClientCacheControlBuilder().staleWhileRevalidate(Duration.ofHours(1))
                                                                     .build();
        assertThat(cc.onlyIfCached()).isFalse();
        assertThat(cc.hasMaxStale()).isFalse();
        assertThat(cc.maxStaleSeconds()).isEqualTo(-1);
        assertThat(cc.minFreshSeconds()).isEqualTo(-1);
        assertThat(cc.staleWhileRevalidateSeconds()).isEqualTo(3600);
        assertThat(cc.staleIfErrorSeconds()).isEqualTo(-1);
        assertThat(cc.asHeaderValue()).isEqualTo("stale-while-revalidate=3600");
        assertThat(cc.toString()).isEqualTo("ClientCacheControl(stale-while-revalidate=3600)");

        assertThat(new ClientCacheControlBuilder().staleWhileRevalidateSeconds(3600).build()).isEqualTo(cc);
    }

    @Test
    public void testStaleIfError() {
        final ClientCacheControl cc = new ClientCacheControlBuilder().staleIfError(Duration.ofHours(1)).build();
        assertThat(cc.onlyIfCached()).isFalse();
        assertThat(cc.hasMaxStale()).isFalse();
        assertThat(cc.maxStaleSeconds()).isEqualTo(-1);
        assertThat(cc.minFreshSeconds()).isEqualTo(-1);
        assertThat(cc.staleWhileRevalidateSeconds()).isEqualTo(-1);
        assertThat(cc.staleIfErrorSeconds()).isEqualTo(3600);
        assertThat(cc.asHeaderValue()).isEqualTo("stale-if-error=3600");
        assertThat(cc.toString()).isEqualTo("ClientCacheControl(stale-if-error=3600)");

        assertThat(new ClientCacheControlBuilder().staleIfErrorSeconds(3600).build()).isEqualTo(cc);
    }

    @Test
    public void testToBuilder() {
        final ClientCacheControl cc = new ClientCacheControlBuilder().onlyIfCached()
                                                                     .maxStaleSeconds(60)
                                                                     .minFreshSeconds(60)
                                                                     .staleWhileRevalidateSeconds(60)
                                                                     .staleIfErrorSeconds(60)
                                                                     .build();
        assertThat(cc.asHeaderValue()).isEqualTo(
                "only-if-cached, max-stale=60, min-fresh=60, stale-while-revalidate=60, stale-if-error=60");
        assertThat(cc.toBuilder().build()).isEqualTo(cc);
        assertThat(cc.toBuilder()
                     .onlyIfCached(false)
                     .maxStale(null)
                     .minFresh(null)
                     .staleWhileRevalidate(null)
                     .staleIfError(null)
                     .build().isEmpty()).isTrue();
    }

    @Test
    public void testParse() {
        // Make sure an empty directives return an empty object.
        assertThat(ClientCacheControl.parse("")).isEqualTo(ClientCacheControl.EMPTY);

        // Make sure unknown directives are ignored.
        assertThat(ClientCacheControl.parse("proxy-revalidate, s-maxage=1"))
                .isEqualTo(ClientCacheControl.EMPTY);

        // Make sure all directives are set.
        assertThat(ClientCacheControl.parse("no-cache, no-store, no-transform, only-if-cached, " +
                                            "max-age=1, max-stale=2, min-fresh=3, " +
                                            "stale-while-revalidate=4, stale-if-error=5"))
                .isEqualTo(new ClientCacheControlBuilder()
                                   .noCache()
                                   .noStore()
                                   .noTransform()
                                   .maxAgeSeconds(1)
                                   .onlyIfCached()
                                   .maxStaleSeconds(2)
                                   .minFreshSeconds(3)
                                   .staleWhileRevalidateSeconds(4)
                                   .staleIfErrorSeconds(5)
                                   .build());

        // Make sure 'max-stale' without a value is parsed.
        assertThat(ClientCacheControl.parse("max-stale"))
                .isEqualTo(new ClientCacheControlBuilder().maxStale().build());
    }
}
