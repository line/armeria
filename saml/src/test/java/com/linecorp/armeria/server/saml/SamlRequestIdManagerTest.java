/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.server.saml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.awaitility.Durations;
import org.junit.Test;

public class SamlRequestIdManagerTest {

    @Test
    public void shouldBeDifferentToEachOther() throws UnsupportedEncodingException {
        final SamlRequestIdManager manager =
                SamlRequestIdManager.ofJwt("me", "test", 60, 5);

        final String id1 = manager.newId();
        final String id2 = manager.newId();
        final String id3 = manager.newId();

        assertThat(id1).isNotEqualTo(id2).isNotEqualTo(id3);
        assertThat(id2).isNotEqualTo(id3);
    }

    @Test
    public void shouldMatchJWTPattern() throws UnsupportedEncodingException {
        final Pattern p = Pattern.compile("[a-zA-Z0-9-_]+\\.[a-zA-Z0-9-_]+\\.[a-zA-Z0-9-_]+");
        final SamlRequestIdManager manager =
                SamlRequestIdManager.ofJwt("me", "test", 60, 5);
        final String id = manager.newId();
        assertThat(p.matcher(id).matches()).isTrue();
        assertThat(manager.validateId(id)).isTrue();
    }

    @Test
    public void shouldBeExpired() throws InterruptedException, UnsupportedEncodingException {
        final SamlRequestIdManager manager =
                SamlRequestIdManager.ofJwt("me", "test", 1, 0);

        final Instant started = Instant.now();
        final String id = manager.newId();
        assertThat(manager.validateId(id)).isTrue();

        await().pollDelay(Durations.TWO_HUNDRED_MILLISECONDS)
               .atMost(Durations.FIVE_SECONDS)
               .untilAsserted(() -> assertThat(manager.validateId(id)).isFalse());

        assertThat(java.time.Duration.between(started, Instant.now()).toMillis())
                .isGreaterThan(TimeUnit.SECONDS.toMillis(1));
    }

    @Test
    public void shouldBeAcceptedBecauseOfLeeway() throws InterruptedException, UnsupportedEncodingException {
        final SamlRequestIdManager manager =
                SamlRequestIdManager.ofJwt("me", "test", 1, 1);

        final Instant started = Instant.now();
        final String id = manager.newId();
        assertThat(manager.validateId(id)).isTrue();

        await().pollDelay(Durations.TWO_HUNDRED_MILLISECONDS)
               .atMost(Durations.FIVE_SECONDS)
               .untilAsserted(() -> assertThat(manager.validateId(id)).isFalse());

        assertThat(java.time.Duration.between(started, Instant.now()).toMillis())
                .isGreaterThan(TimeUnit.SECONDS.toMillis(2));
    }

    @Test
    public void shouldFail() {
        assertThatThrownBy(() -> SamlRequestIdManager.ofJwt("me", "test", 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SamlRequestIdManager.ofJwt("me", "test", -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SamlRequestIdManager.ofJwt("me", "test", 1, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
