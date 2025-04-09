/*
 * Copyright 2025 LY Corporation
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

import static com.linecorp.armeria.server.saml.SamlAssertionConsumerFunction.isSameUri;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SamlAssertionConsumerFunctionTest {

    @Test
    void sameUri() {
        assertThat(isSameUri("https://example.com/saml/redirect", "http://example.com/saml/redirect"))
                .isFalse();
        assertThat(isSameUri("https://example.com/saml/redirect", "https://example1.com/saml/redirect"))
                .isFalse();

        assertThat(isSameUri("https://example.com:443/saml/redirect", "https://example.com/saml/redirect"))
                .isTrue();
        assertThat(isSameUri("http://example.com:80/saml/redirect", "http://example.com/saml/redirect"))
                .isTrue();

        assertThat(isSameUri("https://example.com/saml/redirect", "https://example.com/saml/redirect"))
                .isTrue();
        assertThat(isSameUri("https://example.com:555/saml/redirect", "https://example.com:555/saml/redirect"))
                .isTrue();
    }
}
