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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.internal.common.JacksonUtil;

class SamlEndpointTest {

    @Test
    void deserialize() throws Exception {
        final ObjectMapper objectMapper = JacksonUtil.newDefaultObjectMapper();
        assertThatThrownBy(() -> objectMapper.readValue(
                "{\"uri\":\"https://example.com/\",\"binding\":\"HTTP_REDIRECT\"}",
                SamlEndpoint.class)).hasCauseExactlyInstanceOf(IllegalArgumentException.class)
                                    .hasMessageContaining("uri.path is empty");

        SamlEndpoint samlEndpoint = objectMapper.readValue("{\"uri\":\"/saml/post\"}",
                                                           SamlEndpoint.class);
        // The default binding protocol is HTTP POST.
        assertThat(samlEndpoint.bindingProtocol()).isSameAs(SamlBindingProtocol.HTTP_POST);
        URI uri = samlEndpoint.uri();
        assertThat(uri.getPath()).isEqualTo("/saml/post");
        assertThat(uri.getScheme()).isNull();
        assertThat(uri.getHost()).isNull();
        assertThat(uri.getPort()).isEqualTo(-1);
        assertThat(samlEndpoint.toUriString("http", "example1.com", 36462)).isEqualTo(
                "http://example1.com:36462/saml/post");

        samlEndpoint = objectMapper.readValue("{\"uri\":\"https://example.com/saml/post\"}",
                                              SamlEndpoint.class);
        uri = samlEndpoint.uri();
        assertThat(uri.getPath()).isEqualTo("/saml/post");
        assertThat(uri.getScheme()).isEqualTo("https");
        assertThat(uri.getHost()).isEqualTo("example.com");
        assertThat(uri.getPort()).isEqualTo(-1);

        assertThat(samlEndpoint.toUriString("http", "not-used.com", 36462))
                .isEqualTo("https://example.com/saml/post"); // port is not added.
    }
}
