/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.client.retrofit2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.SessionProtocol;

import retrofit2.Retrofit;

class ArmeriaRetrofitBuilderTest {

    @Test
    void build() throws Exception {
        final Retrofit retrofit = ArmeriaRetrofit.of("http://example.com:8080/");
        assertThat(retrofit.baseUrl().toString()).isEqualTo("http://example.com:8080/");
    }

    @Test
    void build_withoutSlashAtEnd() throws Exception {
        final Retrofit retrofit = ArmeriaRetrofit.of("http://example.com:8080");
        assertThat(retrofit.baseUrl().toString()).isEqualTo("http://example.com:8080/");
    }

    @Test
    void build_withNonRootPath() throws Exception {
        assertThat(ArmeriaRetrofit.of("http://example.com:8080/a/b/c/").baseUrl().toString())
                .isEqualTo("http://example.com:8080/a/b/c/");
    }

    @Test
    void build_withNonRootPathNonSlashEnd() throws Exception {
        assertThatThrownBy(() -> ArmeriaRetrofit.of("http://example.com:8080/a/b/c"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("baseUrl must end in /: http://example.com:8080/a/b/c");
    }

    @Test
    void build_moreSessionProtocol() throws Exception {
        assertThat(ArmeriaRetrofit.of("h1c://example.com:8080/").baseUrl().toString())
                .isEqualTo("http://example.com:8080/");
        assertThat(ArmeriaRetrofit.of("h2c://example.com:8080/").baseUrl().toString())
                .isEqualTo("http://example.com:8080/");
        assertThat(ArmeriaRetrofit.of("h1://example.com:8080/").baseUrl().toString())
                .isEqualTo("https://example.com:8080/");
        assertThat(ArmeriaRetrofit.of("h2://example.com:8080/").baseUrl().toString())
                .isEqualTo("https://example.com:8080/");
        assertThat(ArmeriaRetrofit.of("https://example.com:8080/").baseUrl().toString())
                .isEqualTo("https://example.com:8080/");
    }

    @Test
    void build_armeriaGroupAuthority() throws Exception {
        final Endpoint endpoint = Endpoint.of("127.0.0.1", 8080);
        final EndpointGroup group = EndpointGroup.of(endpoint, endpoint);

        assertThat(ArmeriaRetrofit.of(SessionProtocol.H2C, endpoint).baseUrl().toString())
                .isEqualTo("http://127.0.0.1:8080/");

        assertThat(ArmeriaRetrofit.of(SessionProtocol.H2, group).baseUrl().toString())
                .startsWith("https://armeria-group-");
    }
}
