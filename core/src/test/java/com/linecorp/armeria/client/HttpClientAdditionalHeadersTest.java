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
package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class HttpClientAdditionalHeadersTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of(req.headers().toString()));
        }
    };

    @Test
    void blacklistedHeadersMustBeFiltered() {
        final WebClient client =
                WebClient.builder(server.httpUri())
                         .decorator((delegate, ctx, req) -> {
                             ctx.addAdditionalRequestHeader(HttpHeaderNames.SCHEME, "https");
                             ctx.addAdditionalRequestHeader(HttpHeaderNames.STATUS, "503");
                             ctx.addAdditionalRequestHeader(HttpHeaderNames.METHOD, "CONNECT");
                             ctx.addAdditionalRequestHeader("foo", "bar");
                             return delegate.execute(ctx, req);
                         })
                         .build();

        assertThat(client.get("/").aggregate().join().contentUtf8())
                .doesNotContain("=https")
                .doesNotContain("=503")
                .doesNotContain("=CONNECT")
                .contains("foo=bar");
    }
}
