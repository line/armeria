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
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.Test;

import retrofit2.Retrofit;

public class ArmeriaRetrofitBuilderTest {

    @Test
    public void build() throws Exception {
        Retrofit retrofit = new ArmeriaRetrofitBuilder().baseUrl("http://example.com:8080/").build();
        assertThat(retrofit.baseUrl().toString()).isEqualTo("http://example.com:8080/");
    }

    @Test
    public void build_wrongScheme() throws Exception {
        Throwable thrown = catchThrowable(
                () -> new ArmeriaRetrofitBuilder().baseUrl("foo://example.com:8080").build());
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                          .hasMessage("baseUrl must have an HTTP scheme: foo://example.com:8080");
    }

    @Test
    public void build_withoutSlashAtEnd() throws Exception {
        Retrofit retrofit = new ArmeriaRetrofitBuilder().baseUrl("http://example.com:8080").build();
        assertThat(retrofit.baseUrl().toString()).isEqualTo("http://example.com:8080/");
    }

    @Test
    public void build_withNonRootPath() throws Exception {
        assertThat(new ArmeriaRetrofitBuilder().baseUrl("http://example.com:8080/a/b/c/")
                                               .build().baseUrl().toString())
                .isEqualTo("http://example.com:8080/a/b/c/");
    }

    @Test
    public void build_withNonRootPathNonSlashEnd() throws Exception {
        Throwable thrown = catchThrowable(
                () -> new ArmeriaRetrofitBuilder().baseUrl("http://example.com:8080/a/b/c").build());
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                          .hasMessage("baseUrl must end with /: http://example.com:8080/a/b/c");
    }

    @Test
    public void build_moreSessionProtocol() throws Exception {
        assertThat(new ArmeriaRetrofitBuilder().baseUrl("h1c://example.com:8080/").build().baseUrl()
                                               .toString())
                .isEqualTo("http://example.com:8080/");
        assertThat(new ArmeriaRetrofitBuilder().baseUrl("h2c://example.com:8080/").build().baseUrl()
                                               .toString())
                .isEqualTo("http://example.com:8080/");
        assertThat(new ArmeriaRetrofitBuilder().baseUrl("h1://example.com:8080/").build().baseUrl()
                                               .toString())
                .isEqualTo("https://example.com:8080/");
        assertThat(new ArmeriaRetrofitBuilder().baseUrl("h2://example.com:8080/").build().baseUrl()
                                               .toString())
                .isEqualTo("https://example.com:8080/");
        assertThat(new ArmeriaRetrofitBuilder().baseUrl("https://example.com:8080/").build().baseUrl()
                                               .toString())
                .isEqualTo("https://example.com:8080/");
    }

    @Test
    public void build_armeriaGroupAuthority() throws Exception {
        assertThat(new ArmeriaRetrofitBuilder().baseUrl("http://group:myGroup/").build().baseUrl()
                                               .toString())
                // NB: lower-cased by OkHttp
                .isEqualTo("http://group_mygroup/");

        assertThat(new ArmeriaRetrofitBuilder().baseUrl("http://group:myGroup").build().baseUrl()
                                               .toString())
                .isEqualTo("http://group_mygroup/");
    }
}
