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

public class RedirectSignatureTest {
    @Test
    public void equality() {
        final RedirectSignature signature1 = new RedirectSignature("http://example.com", HttpMethod.GET);
        final RedirectSignature signature2 = new RedirectSignature("http://example.com", HttpMethod.GET);
        final RedirectSignature signature3 = new RedirectSignature("http://another.com", HttpMethod.GET);
        final RedirectSignature signature4 = new RedirectSignature("http://example.com", HttpMethod.POST);

        assertThat(signature1).isEqualTo(signature2);
        assertThat(signature1).isNotEqualTo(signature3);
        assertThat(signature1).isNotEqualTo(signature4);
    }

    @Test
    public void hash() {
        final RedirectSignature signature = new RedirectSignature("http://example.com", HttpMethod.GET);
        final int hash = Objects.hash("http://example.com", HttpMethod.GET);

        assertThat(signature.hashCode()).isEqualTo(hash);
    }
}
