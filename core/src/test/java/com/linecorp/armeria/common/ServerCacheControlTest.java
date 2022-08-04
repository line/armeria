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
package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

public class ServerCacheControlTest {

    @Test
    public void testConstants() {
        assertThat(ServerCacheControl.EMPTY.isEmpty()).isTrue();
        assertThat(ServerCacheControl.DISABLED.asHeaderValue())
                .isEqualTo("no-cache, no-store, max-age=0, must-revalidate");
        assertThat(ServerCacheControl.IMMUTABLE.asHeaderValue())
                .isEqualTo("max-age=31536000, public, immutable");
        assertThat(ServerCacheControl.REVALIDATED.asHeaderValue())
                .isEqualTo("no-cache, max-age=0, must-revalidate");
    }

    @Test
    public void testIsEmpty() {
        final ServerCacheControl cc = ServerCacheControl.builder().build();
        assertThat(cc.isEmpty()).isTrue();
        assertThat(cc.noCache()).isFalse();
        assertThat(cc.noStore()).isFalse();
        assertThat(cc.noTransform()).isFalse();
        assertThat(cc.maxAgeSeconds()).isEqualTo(-1);
        assertThat(cc.cachePublic()).isFalse();
        assertThat(cc.cachePrivate()).isFalse();
        assertThat(cc.immutable()).isFalse();
        assertThat(cc.mustRevalidate()).isFalse();
        assertThat(cc.proxyRevalidate()).isFalse();
        assertThat(cc.sMaxAgeSeconds()).isEqualTo(-1);
        assertThat(cc.asHeaderValue()).isEmpty();
        assertThat(cc.toString()).isEqualTo("ServerCacheControl(<empty>)");
    }

    @Test
    public void testPublic() {
        final ServerCacheControl cc = ServerCacheControl.builder().cachePublic().build();
        assertThat(cc.cachePublic()).isTrue();
        assertThat(cc.cachePrivate()).isFalse();
        assertThat(cc.immutable()).isFalse();
        assertThat(cc.mustRevalidate()).isFalse();
        assertThat(cc.proxyRevalidate()).isFalse();
        assertThat(cc.sMaxAgeSeconds()).isEqualTo(-1);
        assertThat(cc.asHeaderValue()).isEqualTo("public");
        assertThat(cc.toString()).isEqualTo("ServerCacheControl(public)");
    }

    @Test
    public void testPrivate() {
        final ServerCacheControl cc = ServerCacheControl.builder().cachePrivate().build();
        assertThat(cc.cachePublic()).isFalse();
        assertThat(cc.cachePrivate()).isTrue();
        assertThat(cc.immutable()).isFalse();
        assertThat(cc.mustRevalidate()).isFalse();
        assertThat(cc.proxyRevalidate()).isFalse();
        assertThat(cc.sMaxAgeSeconds()).isEqualTo(-1);
        assertThat(cc.asHeaderValue()).isEqualTo("private");
        assertThat(cc.toString()).isEqualTo("ServerCacheControl(private)");
    }

    @Test
    public void testImmutable() {
        final ServerCacheControl cc = ServerCacheControl.builder().immutable().build();
        assertThat(cc.cachePublic()).isFalse();
        assertThat(cc.cachePrivate()).isFalse();
        assertThat(cc.immutable()).isTrue();
        assertThat(cc.mustRevalidate()).isFalse();
        assertThat(cc.proxyRevalidate()).isFalse();
        assertThat(cc.sMaxAgeSeconds()).isEqualTo(-1);
        assertThat(cc.asHeaderValue()).isEqualTo("immutable");
        assertThat(cc.toString()).isEqualTo("ServerCacheControl(immutable)");
    }

    @Test
    public void testMustRevalidate() {
        final ServerCacheControl cc = ServerCacheControl.builder().mustRevalidate().build();
        assertThat(cc.cachePublic()).isFalse();
        assertThat(cc.cachePrivate()).isFalse();
        assertThat(cc.immutable()).isFalse();
        assertThat(cc.mustRevalidate()).isTrue();
        assertThat(cc.proxyRevalidate()).isFalse();
        assertThat(cc.sMaxAgeSeconds()).isEqualTo(-1);
        assertThat(cc.asHeaderValue()).isEqualTo("must-revalidate");
        assertThat(cc.toString()).isEqualTo("ServerCacheControl(must-revalidate)");
    }

    @Test
    public void testProxyRevalidate() {
        final ServerCacheControl cc = ServerCacheControl.builder().proxyRevalidate().build();
        assertThat(cc.cachePublic()).isFalse();
        assertThat(cc.cachePrivate()).isFalse();
        assertThat(cc.immutable()).isFalse();
        assertThat(cc.mustRevalidate()).isFalse();
        assertThat(cc.proxyRevalidate()).isTrue();
        assertThat(cc.sMaxAgeSeconds()).isEqualTo(-1);
        assertThat(cc.asHeaderValue()).isEqualTo("proxy-revalidate");
        assertThat(cc.toString()).isEqualTo("ServerCacheControl(proxy-revalidate)");
    }

    @Test
    public void testSMaxAge() {
        final ServerCacheControl cc = ServerCacheControl.builder().sMaxAge(Duration.ofMinutes(1)).build();
        assertThat(cc.cachePublic()).isFalse();
        assertThat(cc.cachePrivate()).isFalse();
        assertThat(cc.immutable()).isFalse();
        assertThat(cc.mustRevalidate()).isFalse();
        assertThat(cc.proxyRevalidate()).isFalse();
        assertThat(cc.sMaxAgeSeconds()).isEqualTo(60);
        assertThat(cc.asHeaderValue()).isEqualTo("s-maxage=60");
        assertThat(cc.toString()).isEqualTo("ServerCacheControl(s-maxage=60)");

        assertThat(ServerCacheControl.builder().sMaxAgeSeconds(60).build()).isEqualTo(cc);
    }

    @Test
    public void testToBuilder() {
        final ServerCacheControl cc = ServerCacheControl.builder()
                                                        .cachePublic()
                                                        .cachePrivate()
                                                        .immutable()
                                                        .mustRevalidate()
                                                        .proxyRevalidate()
                                                        .sMaxAgeSeconds(3600)
                                                        .build();

        // 'public' and 'private' are mutually exclusive. 'private' must win.
        assertThat(cc.asHeaderValue())
                .isEqualTo("private, immutable, must-revalidate, proxy-revalidate, s-maxage=3600");
        assertThat(cc.toBuilder().build()).isEqualTo(cc);
        assertThat(cc.toBuilder()
                     .cachePrivate(false)
                     .immutable(false)
                     .mustRevalidate(false)
                     .proxyRevalidate(false)
                     .sMaxAge(null)
                     .build().isEmpty()).isTrue();
    }

    @Test
    public void testParse() {
        // Make sure an empty directives return an empty object.
        assertThat(ServerCacheControl.parse("")).isEqualTo(ServerCacheControl.EMPTY);

        // Make sure unknown directives are ignored.
        assertThat(ServerCacheControl.parse("only-if-cached, stall-if-error=1"))
                .isEqualTo(ServerCacheControl.EMPTY);

        // Make sure all directives are set.
        assertThat(ServerCacheControl.parse("no-cache, no-store, no-transform, must-revalidate, " +
                                            "max-age=1, public, private, immutable, proxy-revalidate, " +
                                            "s-maxage=2"))
                .isEqualTo(ServerCacheControl.builder()
                                             .noCache()
                                             .noStore()
                                             .noTransform()
                                             .maxAgeSeconds(1)
                                             .cachePrivate()
                                             .immutable()
                                             .mustRevalidate()
                                             .proxyRevalidate()
                                             .sMaxAgeSeconds(2)
                                             .build());
    }
}
