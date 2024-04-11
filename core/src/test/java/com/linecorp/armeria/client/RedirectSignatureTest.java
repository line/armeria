/*
 * Copyright 2024 LINE Corporation
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

import java.util.Objects;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.RedirectingClient.RedirectSignature;
import com.linecorp.armeria.common.HttpMethod;

class RedirectSignatureTest {

    private static final String PROTOCOL = "http";
    private static final String AUTHORITY = "example.com";
    private static final String PATH_AND_QUERY = "/query?q=1";
    private static final HttpMethod METHOD = HttpMethod.GET;

    private static final RedirectSignature SIGNATURE =
            new RedirectSignature(PROTOCOL, AUTHORITY, PATH_AND_QUERY, METHOD);

    @Test
    void equalityWithSameObject() {
        assertThat(SIGNATURE.equals(SIGNATURE)).isTrue();
    }

    @Test
    void equalityWithDifferentType() {
        final Object other = new Object();
        assertThat(SIGNATURE).isNotEqualTo(other);
    }

    @Test
    void equalityWithEquivalentObjects() {
        final RedirectSignature signature =
                new RedirectSignature(PROTOCOL, AUTHORITY, PATH_AND_QUERY, METHOD);
        assertThat(SIGNATURE).isEqualTo(signature);
    }

    @Test
    void equalityWithNonEquivalentObjects() {
        final RedirectSignature differentProtocolSignature =
                new RedirectSignature("https", AUTHORITY, PATH_AND_QUERY, METHOD);
        final RedirectSignature differentAuthoritySignature =
                new RedirectSignature(PROTOCOL, "another.com", PATH_AND_QUERY, METHOD);
        final RedirectSignature differentPathAndQuerySignature =
                new RedirectSignature(PROTOCOL, AUTHORITY, "/another?q=2", METHOD);
        final RedirectSignature differentMethodSignature =
                new RedirectSignature(PROTOCOL, AUTHORITY, PATH_AND_QUERY, HttpMethod.POST);
        assertThat(SIGNATURE).isNotEqualTo(differentProtocolSignature);
        assertThat(SIGNATURE).isNotEqualTo(differentAuthoritySignature);
        assertThat(SIGNATURE).isNotEqualTo(differentPathAndQuerySignature);
        assertThat(SIGNATURE).isNotEqualTo(differentMethodSignature);
    }

    @Test
    void hash() {
        final int hash = Objects.hash(PROTOCOL, AUTHORITY, PATH_AND_QUERY, METHOD);

        assertThat(SIGNATURE.hashCode()).isEqualTo(hash);
    }

    @Test
    void uri() {
        assertThat(SIGNATURE.uri()).isEqualTo("http://example.com/query?q=1");
    }
}
