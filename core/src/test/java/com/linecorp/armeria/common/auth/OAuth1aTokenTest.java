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
package com.linecorp.armeria.common.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;

class OAuth1aTokenTest {
    @Test
    void testEquals() {
        final OAuth1aToken token = AuthToken.builderForOAuth1a()
                                            .consumerKey("a")
                                            .token("b")
                                            .signatureMethod("c")
                                            .signature("d")
                                            .timestamp("0")
                                            .nonce("f")
                                            .put("x-others", "g")
                                            .build();
        final ImmutableMap.Builder<String, String> paramsBuilder = ImmutableMap.builder();
        paramsBuilder.put("oauth_consumer_key", "a");
        paramsBuilder.put("oauth_token", "b");
        paramsBuilder.put("oauth_signature_method", "c");
        paramsBuilder.put("oauth_signature", "d");
        paramsBuilder.put("oauth_timestamp", "0");
        paramsBuilder.put("oauth_nonce", "f");
        paramsBuilder.put("x-others", "g");
        assertThat(token).isEqualTo(
                AuthToken.builderForOAuth1a().putAll(paramsBuilder.build()).build());
        assertThat(token).isNotEqualTo(AuthToken.builderForOAuth1a()
                                                .consumerKey("a")
                                                .token("b")
                                                .signatureMethod("c")
                                                .signature("d")
                                                .timestamp("0")
                                                .nonce("f")
                                                .build());
        assertThat(token).isNotEqualTo(AuthToken.builderForOAuth1a()
                                                .consumerKey("a")
                                                .token("b")
                                                .signatureMethod("c")
                                                .signature("d")
                                                .timestamp("0")
                                                .nonce("f")
                                                .putAll(ImmutableMap.of("x-others", "g",
                                                                                 "x-others-2", "h"))
                                                .build());
    }
}
