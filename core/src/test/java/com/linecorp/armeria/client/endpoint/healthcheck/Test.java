/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.client.endpoint.healthcheck;

import java.util.concurrent.ExecutionException;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestHeaders;

public class Test {

    @org.junit.jupiter.api.Test
    void test() throws ExecutionException, InterruptedException {
        final WebClient client = WebClient.builder("https://google.com")
                .build();
        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/maps")).aggregate().get();
        System.out.println(response.headers());
        final WebClient client1 = WebClient.builder("https://google.com").build();
        final AggregatedHttpResponse response1 =
                client1.execute(RequestHeaders.of(HttpMethod.GET, "/maps")).aggregate().get();
        System.out.println(response1.headers());

        final WebClient client2 = WebClient.builder("https://tesla.com").build();
        final AggregatedHttpResponse response2 =
                client2.execute(RequestHeaders.of(HttpMethod.GET, "/models")).aggregate().get();
        System.out.println(response2.headers());
    }
}
