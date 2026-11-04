/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import static com.linecorp.armeria.server.saml.SamlServiceProviderBuilder.isSafeRelayState;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RelayStateOpenRedirectTest {

    private static final int MAX_LENGTH = 80;

    @ParameterizedTest
    @ValueSource(strings = {
            // Absolute URLs to an attacker-controlled origin.
            "https://evil.example/phish",
            "http://evil.example/phish",
            // Protocol-relative URL which still resolves to an attacker host.
            "//evil.example/",
            // Dangerous schemes that must never be emitted by the SP.
            "javascript:alert(document.domain)",
            "data:text/html,<script>alert(1)</script>",
            // Backslashes are normalized to '/' by some browsers, enabling '/\\evil.example'.
            "/\\evil.example",
            "\\\\evil.example",
            // Control characters used to smuggle a redirect target.
            "/foo\r\nLocation: https://evil.example",
            "/foo\tbar",
            // Not a path at all.
            "evil.example",
            "",
    })
    void unsafeRelayStateIsRejected(String relayState) {
        assertThat(isSafeRelayState(relayState, MAX_LENGTH)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/",
            "/dashboard",
            "/dashboard?next=/home",
            "/a/b/c",
    })
    void relativePathRelayStateIsAccepted(String relayState) {
        assertThat(isSafeRelayState(relayState, MAX_LENGTH)).isTrue();
    }

    @Test
    void relayStateExceedingMaxLengthIsRejected() {
        final StringBuilder sb = new StringBuilder("/");
        for (int i = 0; i < MAX_LENGTH; i++) {
            sb.append('a');
        }
        assertThat(sb.length()).isGreaterThan(MAX_LENGTH);
        assertThat(isSafeRelayState(sb.toString(), MAX_LENGTH)).isFalse();
    }
}
