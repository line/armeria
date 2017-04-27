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

import java.net.URI;

import org.junit.Test;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.http.HttpClient;

import retrofit2.Retrofit;

public class ArmeriaRetrofitTest {
    @Test
    public void convertToOkHttpUrl() throws Exception {
        Retrofit retrofit = ArmeriaRetrofit.builder(
                Clients.newClient(URI.create("none+http://example.com:8080/a/b/c/"), HttpClient.class)).build();
        assertThat(retrofit.baseUrl().toString())
                .isEqualTo("http://example.com:8080/a/b/c/");
    }

    @Test
    public void convertToOkHttpUrl_sessionProtocol() throws Exception {
        assertThat(ArmeriaRetrofit.builder(URI.create("h1c://example.com:8080/")).build().baseUrl().toString())
                .isEqualTo("http://example.com:8080/");
        assertThat(ArmeriaRetrofit.builder(URI.create("h2c://example.com:8080/")).build().baseUrl().toString())
                .isEqualTo("http://example.com:8080/");
        assertThat(ArmeriaRetrofit.builder(URI.create("h1://example.com:8080/")).build().baseUrl().toString())
                .isEqualTo("https://example.com:8080/");
        assertThat(ArmeriaRetrofit.builder(URI.create("h2://example.com:8080/")).build().baseUrl().toString())
                .isEqualTo("https://example.com:8080/");
        assertThat(ArmeriaRetrofit.builder(URI.create("https://example.com:8080/")).build().baseUrl()
                                  .toString()).isEqualTo("https://example.com:8080/");
    }

    @Test
    public void convertToOkHttpUrl_convertOkhttpNotSupportedAuthority() throws Exception {
        assertThat(ArmeriaRetrofit.builder(URI.create("http://group:myGroup/path/")).build().baseUrl()
                                  .toString())
                // NB: lower-cased by OkHttp
                .isEqualTo("http://group_mygroup/path/");
    }
}
