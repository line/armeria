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
package com.linecorp.armeria.server.auth;

import static com.linecorp.armeria.server.auth.OAuth1aToken.OAUTH_CONSUMER_KEY;
import static com.linecorp.armeria.server.auth.OAuth1aToken.OAUTH_NONCE;
import static com.linecorp.armeria.server.auth.OAuth1aToken.OAUTH_SIGNATURE;
import static com.linecorp.armeria.server.auth.OAuth1aToken.OAUTH_SIGNATURE_METHOD;
import static com.linecorp.armeria.server.auth.OAuth1aToken.OAUTH_TIMESTAMP;
import static com.linecorp.armeria.server.auth.OAuth1aToken.OAUTH_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;

class OAuth1aTokenTest {
    @Test
    void testEquals() {
        final OAuth1aToken token = OAuth1aToken.of(ImmutableMap.<String, String>builder()
                                                           .put(OAUTH_CONSUMER_KEY, "a")
                                                           .put(OAUTH_TOKEN, "b")
                                                           .put(OAUTH_SIGNATURE_METHOD, "c")
                                                           .put(OAUTH_SIGNATURE, "d")
                                                           .put(OAUTH_TIMESTAMP, "0")
                                                           .put(OAUTH_NONCE, "f")
                                                           .put("x-others", "g")
                                                           .build());
        assertThat(token).isEqualTo(OAuth1aToken.of(ImmutableMap.<String, String>builder()
                                                            .put(OAUTH_CONSUMER_KEY, "a")
                                                            .put(OAUTH_TOKEN, "b")
                                                            .put(OAUTH_SIGNATURE_METHOD, "c")
                                                            .put(OAUTH_SIGNATURE, "d")
                                                            .put(OAUTH_TIMESTAMP, "0")
                                                            .put(OAUTH_NONCE, "f")
                                                            .put("x-others", "g")
                                                            .build()));
        assertThat(token).isNotEqualTo(OAuth1aToken.of(ImmutableMap.<String, String>builder()
                                                            .put(OAUTH_CONSUMER_KEY, "1")
                                                            .put(OAUTH_TOKEN, "2")
                                                            .put(OAUTH_SIGNATURE_METHOD, "3")
                                                            .put(OAUTH_SIGNATURE, "4")
                                                            .put(OAUTH_TIMESTAMP, "5")
                                                            .put(OAUTH_NONCE, "6")
                                                            .put("x-others", "7")
                                                            .build()));
        assertThat(token).isNotEqualTo(OAuth1aToken.of(ImmutableMap.<String, String>builder()
                                                            .put(OAUTH_CONSUMER_KEY, "a")
                                                            .put(OAUTH_TOKEN, "b")
                                                            .put(OAUTH_SIGNATURE_METHOD, "c")
                                                            .put(OAUTH_SIGNATURE, "d")
                                                            .put(OAUTH_TIMESTAMP, "0")
                                                            .put(OAUTH_NONCE, "f")
                                                            .build()));
        assertThat(token).isNotEqualTo(OAuth1aToken.of(ImmutableMap.<String, String>builder()
                                                            .put(OAUTH_CONSUMER_KEY, "a")
                                                            .put(OAUTH_TOKEN, "b")
                                                            .put(OAUTH_SIGNATURE_METHOD, "c")
                                                            .put(OAUTH_SIGNATURE, "d")
                                                            .put(OAUTH_TIMESTAMP, "0")
                                                            .put(OAUTH_NONCE, "f")
                                                            .put("x-others", "g")
                                                            .put("x-others-2", "h")
                                                            .build()));
    }
}
