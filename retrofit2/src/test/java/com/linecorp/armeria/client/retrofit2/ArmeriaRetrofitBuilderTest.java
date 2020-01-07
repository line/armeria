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
        final Retrofit retrofit = ArmeriaRetrofit.of("http://example.com:8080/a/b/c/");
        assertThat(retrofit.baseUrl().toString()).isEqualTo("http://example.com:8080/a/b/c/");
    }

    @Test
    void build_withNonRootPathNonSlashEnd() throws Exception {
        assertThatThrownBy(() -> ArmeriaRetrofit.of("http://example.com:8080/a/b/c"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("baseUrl must end in /: http://example.com:8080/a/b/c");
    }
}
