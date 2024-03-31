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
import static org.junit.Assert.assertTrue;

import java.util.Objects;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.RedirectingClient.RedirectSignature;
import com.linecorp.armeria.common.HttpMethod;

public class RedirectSignatureTest {
    @Test
    public void equalityWithSameObject() {
        final RedirectSignature signature = new RedirectSignature("http://example.com", HttpMethod.GET);
        assertThat(signature.equals(signature)).isTrue();
    }

    @Test
    public void equalityWithDifferentType() {
        final RedirectSignature signature = new RedirectSignature("http://example.com", HttpMethod.GET);
        final Object other = new Object();
        assertThat(signature).isNotEqualTo(other);
    }

    @Test
    public void equalityWithEquivalentObjects() {
        final RedirectSignature signature1 = new RedirectSignature("http://example.com", HttpMethod.GET);
        final RedirectSignature signature2 = new RedirectSignature("http://example.com", HttpMethod.GET);
        assertThat(signature1).isEqualTo(signature2);
    }

    @Test
    public void equalityWithNonEquivalentObjects() {
        final RedirectSignature signature = new RedirectSignature("http://example.com", HttpMethod.GET);
        final RedirectSignature differentUriSignature =
                new RedirectSignature("http://another.com", HttpMethod.GET);
        final RedirectSignature differentMethodSignature =
                new RedirectSignature("http://example.com", HttpMethod.POST);
        assertThat(signature).isNotEqualTo(differentUriSignature);
        assertThat(signature).isNotEqualTo(differentMethodSignature);
    }

    @Test
    public void hash() {
        final RedirectSignature signature = new RedirectSignature("http://example.com", HttpMethod.GET);
        final int hash = Objects.hash("http://example.com", HttpMethod.GET);

        assertThat(signature.hashCode()).isEqualTo(hash);
    }
}
