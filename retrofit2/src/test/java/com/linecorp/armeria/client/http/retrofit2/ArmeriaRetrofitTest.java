/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client.http.retrofit2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;

import org.junit.Test;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.http.HttpClient;

public class ArmeriaRetrofitTest {
    @Test
    public void convertToOkHttpUrl() throws Exception {
        URI uri = Clients.newClient(URI.create("none+http://example.com:8080/a/b/c"), HttpClient.class).uri();
        assertThat(String.valueOf(ArmeriaRetrofit.convertToOkHttpUrl(uri)))
                .isEqualTo("http://example.com:8080/a/b/c");
    }

    @Test
    public void convertToOkHttpUrl_sessionProtocol() throws Exception {
        assertThat(String.valueOf(ArmeriaRetrofit.convertToOkHttpUrl(URI.create("h1c://example.com:8080/"))))
                .isEqualTo("http://example.com:8080/");
        assertThat(String.valueOf(ArmeriaRetrofit.convertToOkHttpUrl(URI.create("h2c://example.com:8080/"))))
                .isEqualTo("http://example.com:8080/");
        assertThat(String.valueOf(ArmeriaRetrofit.convertToOkHttpUrl(URI.create("h1://example.com:8080/"))))
                .isEqualTo("https://example.com:8080/");
        assertThat(String.valueOf(ArmeriaRetrofit.convertToOkHttpUrl(URI.create("h2://example.com:8080/"))))
                .isEqualTo("https://example.com:8080/");
        assertThat(String.valueOf(ArmeriaRetrofit.convertToOkHttpUrl(URI.create("https://example.com:8080/"))))
                .isEqualTo("https://example.com:8080/");
    }

    @Test
    public void convertToOkHttpUrl_wrongSessionProtocol() throws Exception {
        assertThatThrownBy(() -> ArmeriaRetrofit.convertToOkHttpUrl(URI.create("foo://example.com:8080/")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void convertToOkHttpUrl_noSerializationFormat() throws Exception {
        URI uri = URI.create("http://example.com:8080/");
        assertThat(String.valueOf(ArmeriaRetrofit.convertToOkHttpUrl(uri)))
                .isEqualTo("http://example.com:8080/");
    }

    @Test
    public void convertToOkHttpUrl_convertOkhttpNotSupportedAuthority() throws Exception {
        URI uri = URI.create("http://group:myGroup/path");
        assertThat(String.valueOf(ArmeriaRetrofit.convertToOkHttpUrl(uri)))
                .isEqualTo("http://group_mygroup/path"); // NB: lower-cased by OkHttp
    }
}
