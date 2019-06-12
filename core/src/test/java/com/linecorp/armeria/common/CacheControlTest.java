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

public class CacheControlTest {

    @Test
    public void testIsEmpty() {
        final CacheControl cc = new CacheControlImplBuilder().build();
        assertThat(cc.isEmpty()).isTrue();
        assertThat(cc.noCache()).isFalse();
        assertThat(cc.noStore()).isFalse();
        assertThat(cc.noTransform()).isFalse();
        assertThat(cc.maxAgeSeconds()).isEqualTo(-1);
        assertThat(cc.asHeaderValue()).isEmpty();
        assertThat(cc.toString()).isEqualTo("CacheControlImpl(<empty>)");
    }

    @Test
    public void testNoCache() {
        final CacheControl cc = new CacheControlImplBuilder().noCache().build();
        assertThat(cc.isEmpty()).isFalse();
        assertThat(cc.noCache()).isTrue();
        assertThat(cc.noStore()).isFalse();
        assertThat(cc.noTransform()).isFalse();
        assertThat(cc.maxAgeSeconds()).isEqualTo(-1);
        assertThat(cc.asHeaderValue()).isEqualTo("no-cache");
        assertThat(cc.toString()).isEqualTo("CacheControlImpl(no-cache)");
    }

    @Test
    public void testNoStore() {
        final CacheControl cc = new CacheControlImplBuilder().noStore().build();
        assertThat(cc.isEmpty()).isFalse();
        assertThat(cc.noCache()).isFalse();
        assertThat(cc.noStore()).isTrue();
        assertThat(cc.noTransform()).isFalse();
        assertThat(cc.maxAgeSeconds()).isEqualTo(-1);
        assertThat(cc.asHeaderValue()).isEqualTo("no-store");
        assertThat(cc.toString()).isEqualTo("CacheControlImpl(no-store)");
    }

    @Test
    public void testNoTransform() {
        final CacheControl cc = new CacheControlImplBuilder().noTransform().build();
        assertThat(cc.isEmpty()).isFalse();
        assertThat(cc.noCache()).isFalse();
        assertThat(cc.noStore()).isFalse();
        assertThat(cc.noTransform()).isTrue();
        assertThat(cc.maxAgeSeconds()).isEqualTo(-1);
        assertThat(cc.asHeaderValue()).isEqualTo("no-transform");
        assertThat(cc.toString()).isEqualTo("CacheControlImpl(no-transform)");
    }

    @Test
    public void testMaxAge() {
        final CacheControl cc = new CacheControlImplBuilder().maxAge(Duration.ofDays(1)).build();
        assertThat(cc.isEmpty()).isFalse();
        assertThat(cc.noCache()).isFalse();
        assertThat(cc.noStore()).isFalse();
        assertThat(cc.noTransform()).isFalse();
        assertThat(cc.maxAgeSeconds()).isEqualTo(86400);
        assertThat(cc.asHeaderValue()).isEqualTo("max-age=86400");
        assertThat(cc.toString()).isEqualTo("CacheControlImpl(max-age=86400)");

        assertThat(new CacheControlImplBuilder().maxAgeSeconds(86400).build()).isEqualTo(cc);
    }

    @Test
    public void testToBuilder() {
        final CacheControl cc = new CacheControlImplBuilder().noCache()
                                                             .noStore()
                                                             .noTransform()
                                                             .maxAgeSeconds(3600)
                                                             .build();
        assertThat(cc.asHeaderValue()).isEqualTo("no-cache, no-store, no-transform, max-age=3600");
        assertThat(cc.toBuilder().build()).isEqualTo(cc);
        assertThat(cc.toBuilder()
                     .noCache(false)
                     .noStore(false)
                     .noTransform(false)
                     .maxAge(null)
                     .build().isEmpty()).isTrue();
    }

    private static final class CacheControlImplBuilder extends CacheControlBuilder {

        CacheControlImplBuilder() {}

        CacheControlImplBuilder(CacheControl c) {
            super(c);
        }

        @Override
        protected CacheControlImpl build(boolean noCache, boolean noStore,
                                         boolean noTransform, long maxAgeSeconds) {
            return new CacheControlImpl(noCache, noStore, noTransform, maxAgeSeconds);
        }
    }

    private static final class CacheControlImpl extends CacheControl {

        private CacheControlImpl(boolean noCache, boolean noStore, boolean noTransform, long maxAgeSeconds) {
            super(noCache, noStore, noTransform, maxAgeSeconds);
        }

        @Override
        public CacheControlBuilder toBuilder() {
            return new CacheControlImplBuilder(this);
        }

        @Override
        public String asHeaderValue() {
            final StringBuilder buf = newHeaderValueBuffer();
            return buf.length() == 0 ? "" : buf.substring(2);
        }
    }
}
