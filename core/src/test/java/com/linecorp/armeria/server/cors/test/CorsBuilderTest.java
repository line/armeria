/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.server.cors.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.server.cors.CorsPolicy;
import com.linecorp.armeria.server.cors.CorsPolicyBuilder;

class CorsBuilderTest {

    @Test
    void buildCorsPolicy() {
        final String url = "http://example.com";
        final CorsPolicyBuilder cb = CorsPolicy.builder(url);
        cb.allowRequestMethods(HttpMethod.POST, HttpMethod.GET)
          .allowRequestHeaders("allow_request_header")
          .exposeHeaders("expose_header_1", "expose_header_2")
          .preflightResponseHeader("x-preflight-cors", "Hello CORS");
        final CorsPolicy policy = cb.build();
        assertThat(policy.originPredicate().test(url)).isTrue();
    }
}
