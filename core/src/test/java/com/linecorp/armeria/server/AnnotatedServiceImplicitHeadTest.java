/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AnnotatedServiceImplicitHeadTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService(new Object() {
                @Get("/test")
                public HttpResponse hello() {
                    return HttpResponse.of("Hello");
                }
            });
        }
    };

    @Test
    void getAnnotationMatchesHeadMethod() {
        final BlockingWebClient webClient = server.blockingWebClient();
        final AggregatedHttpResponse res = webClient.head("/test");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getAnnotationDoesNotMatchDeleteMethod() {
        final BlockingWebClient webClient = server.blockingWebClient();
        final AggregatedHttpResponse res = webClient.delete("/test");
        assertThat(res.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void testHeadMethodWithNonExistencePath() {
        final BlockingWebClient webClient = server.blockingWebClient();
        final AggregatedHttpResponse res = webClient.head("/invalid");
        assertThat(res.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
