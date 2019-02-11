/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.client.retry;

import static com.linecorp.armeria.common.HttpMethod.GET;
import static com.linecorp.armeria.common.HttpStatus.SERVICE_UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroupRegistry;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.endpoint.StaticEndpointGroup;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.server.ServerRule;

public class RetryingClientAuthorityHeaderTest {

    @ClassRule
    public static ServerRule backend1 = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", new AbstractHttpService() {

                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    return HttpResponse.of(SERVICE_UNAVAILABLE);
                }
            });
        }
    };

    @ClassRule
    public static ServerRule backend2 = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", new AbstractHttpService() {

                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    return HttpResponse.of(req.headers().authority());
                }
            });
        }
    };

    @Test
    public void authorityIsDifferentByBackendsWhenRetry() {
        final HttpClient client = newHttpClientWithEndpointGroup();

        final AggregatedHttpMessage msg = client.get("/").aggregate().join();
        assertThat(msg.contentUtf8()).contains("www.bar.com");
    }

    @Test
    public void authorityIsSameWhenSet() {
        final HttpClient client = newHttpClientWithEndpointGroup();

        final HttpHeaders headers = HttpHeaders.of(GET, "/").authority("www.armeria.com");
        final AggregatedHttpMessage msg = client.execute(headers).aggregate().join();
        assertThat(msg.contentUtf8()).contains("www.armeria.com");
    }

    private static HttpClient newHttpClientWithEndpointGroup() {
        final EndpointGroup endpointGroup = new StaticEndpointGroup(
                Endpoint.of("www.foo.com", backend1.httpPort()).withIpAddr("127.0.0.1"),
                Endpoint.of("www.bar.com", backend2.httpPort()).withIpAddr("127.0.0.1"));
        EndpointGroupRegistry.register("backends", endpointGroup, EndpointSelectionStrategy.ROUND_ROBIN);

        return new HttpClientBuilder("h2c://group:backends")
                .decorator(RetryingHttpClient.newDecorator(RetryStrategy.onServerErrorStatus()))
                .build();
    }
}
