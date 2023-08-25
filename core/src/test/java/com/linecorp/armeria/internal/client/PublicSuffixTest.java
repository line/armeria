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

package com.linecorp.armeria.internal.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;

@GenerateNativeImageTrace
class PublicSuffixTest {

    private static final PublicSuffix ps = PublicSuffix.get();

    private static void assertPublicSuffix(String domain) {
        assertThat(ps.isPublicSuffix(domain)).isTrue();
    }

    private static void assertNotPublicSuffix(String domain) {
        assertThat(ps.isPublicSuffix(domain)).isFalse();
    }

    @Test
    void isPublicSuffix() {
        assertPublicSuffix("com");
        assertNotPublicSuffix("a.com");
        assertPublicSuffix("biz");
        assertPublicSuffix("biz.vn");
        assertNotPublicSuffix("a.biz.vn");
        assertPublicSuffix("uk.com");
        assertPublicSuffix("com.ac");
        assertNotPublicSuffix("test.ac");
        assertPublicSuffix("mm");
        assertPublicSuffix("c.mm");
        assertNotPublicSuffix("b.c.mm");
        assertNotPublicSuffix("a.b.c.mm");
        assertPublicSuffix("jp");
        assertNotPublicSuffix("a.jp");
        assertPublicSuffix("kyoto.jp");
        assertNotPublicSuffix("a.kyoto.jp");
        assertPublicSuffix("kawasaki.jp");
        assertPublicSuffix("a.kawasaki.jp");
        assertNotPublicSuffix("city.kawasaki.jp");
        assertPublicSuffix("ck");
        assertPublicSuffix("a.ck");
        assertNotPublicSuffix("a.b.ck");
        assertNotPublicSuffix("www.ck");
        assertPublicSuffix("compute.amazonaws.com");
        assertPublicSuffix("b.compute.amazonaws.com");
        assertPublicSuffix("b.compute.amazonaws.com.cn");
        assertNotPublicSuffix("a.b.compute.amazonaws.com");
        assertPublicSuffix("dev.adobeaemcloud.com");
        assertPublicSuffix("a.dev.adobeaemcloud.com");
        assertPublicSuffix("xn--12c1fe0br.xn--o3cw4h");
        assertNotPublicSuffix("xn--12c1fe0br.xn--12c1fe0br.xn--o3cw4h");
        assertPublicSuffix("xn--mgbi4ecexp");
    }
}
