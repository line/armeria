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
package com.linecorp.armeria.server.file;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ServerCacheControl;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class FileServiceAdditionalHeadersTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.serviceUnder(
                    "/",
                    FileService.builder(getClass().getClassLoader(), "/")
                               .addHeader("foo", "1")
                               .addHeader("foo", "2")
                               .setHeader("bar", "3")
                               .cacheControl(ServerCacheControl.REVALIDATED)
                               .build());
        }
    };

    @Test
    void testAdditionalHeaders() {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/java/lang/Object.class");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().getAll(HttpHeaderNames.of("foo"))).containsExactly("1", "2");
        assertThat(res.headers().getAll(HttpHeaderNames.of("bar"))).containsExactly("3");
        assertThat(res.headers().getAll(HttpHeaderNames.CACHE_CONTROL))
                .containsExactly("no-cache, max-age=0, must-revalidate");
    }
}
