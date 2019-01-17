/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.server.cors;

/**
 * Builds a new {@link CorsPolicy}.
 * <h2>Example</h2>
 * <pre>{@code
 * CorsPolicyBuilder cb = new CorsPolicyBuilder("http://example.com");
 * cb.allowRequestMethods(HttpMethod.POST, HttpMethod.GET)
 *   .allowRequestHeaders("allow_request_header")
 *   .exposeHeaders("expose_header_1", "expose_header_2")
 *   .preflightResponseHeader("x-preflight-cors", "Hello CORS");
 * CorsPolicy policy = cb.build();
 * }</pre>
 *
 */
public final class CorsPolicyBuilder extends AbstractCorsPolicyBuilder<CorsPolicyBuilder> {
    /**
     * Creates a new instance with the specified {@code origins}.
     */
    public CorsPolicyBuilder(String... origins) {
        super(origins);
    }

    /**
     * Returns a newly-created {@link CorsPolicy} based on the properties of this builder.
     */
    @Override
    public CorsPolicy build() {
        return super.build();
    }
}
