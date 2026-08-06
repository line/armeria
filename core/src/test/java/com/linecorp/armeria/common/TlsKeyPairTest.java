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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.util.SystemInfo;

class TlsKeyPairTest {

    @Test
    void selfSignedWithLocalHostname() {
        // Must not fail even on a machine whose hostname exceeds the 64-character common name limit
        // of RFC 5280, such as a GitHub Actions macOS runner.
        final TlsKeyPair keyPair = TlsKeyPair.ofSelfSigned();
        assertThat(keyPair.certificateChain()).hasSize(1);
        final String hostname = SystemInfo.hostname();
        final String expectedCommonName = hostname.length() <= 64 ? hostname : hostname.substring(0, 64);
        assertThat(keyPair.certificateChain().get(0).getSubjectX500Principal().getName())
                .isEqualTo("CN=" + expectedCommonName);
    }
}
