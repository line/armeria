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
package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.metric.MoreMeters;

public class DnsMetricsTest {

    @Test
    void dns_metric_test_for_successful_query_writes() throws ExecutionException, InterruptedException {
        final ClientFactory factory = ClientFactory.builder()
                .build();

        final WebClient client2 = WebClient.builder()
                .factory(factory)
                .build();

        client2.execute(RequestHeaders.of(HttpMethod.GET, "http://wikipedia.com")).aggregate().get();
        System.out.println(MoreMeters.measureAll(factory.dnsMetricRegistry()));
        final double count = factory.dnsMetricRegistry().getPrometheusRegistry()
                .getSampleValue("armeria_client_dns_queries_total",
                        new String[] {"cause","name","result"},
                        new String[] {"","wikipedia.com.", "success"});
        assertThat(count > 1.0).isTrue();
    }

    @Test
    void dns_metric_test_for_query_failures() throws ExecutionException, InterruptedException {
        final ClientFactory factory = ClientFactory.builder()
                .build();
        try {
            final WebClient client2 = WebClient.builder()
                    .factory(factory)
                    .build();
            client2.execute(RequestHeaders.of(HttpMethod.GET, "http://googleusercontent.com")).aggregate().get();
        } catch (Exception ex) {
            final double count = factory.dnsMetricRegistry().getPrometheusRegistry()
                    .getSampleValue("armeria_client_dns_queries_total",
                            new String[] {"cause","name","result"},
                            new String[] {"No matching record type found","googleusercontent.com.", "failure"});
            assertThat(count > 1.0).isTrue();
        }
    }
}
