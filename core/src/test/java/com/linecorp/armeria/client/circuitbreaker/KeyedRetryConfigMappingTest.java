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

package com.linecorp.armeria.client.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.retry.RetryConfig;
import com.linecorp.armeria.client.retry.RetryConfigMapping;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;

class KeyedRetryConfigMappingTest {

    @Test
    void mapsCorrectly() throws Exception {
        final BiFunction<ClientRequestContext, Request, String> keyFactory =
                (ctx, req) -> ctx.endpoint().host() + '#' + ctx.path();
        final BiFunction<ClientRequestContext, Request, RetryConfig> configFactory = (ctx, req) -> {
            if (ctx.endpoint().host().equals("host1")) {
                return RetryConfig.builder()
                                  .maxTotalAttempts(1).responseTimeoutMillisForEachAttempt(1000).build();
            } else if (ctx.endpoint().host().equals("host2")) {
                if (ctx.path().equals("/path2")) {
                    return RetryConfig.builder()
                                      .maxTotalAttempts(2).responseTimeoutMillisForEachAttempt(2000).build();
                } else {
                    return RetryConfig.builder()
                                      .maxTotalAttempts(3).responseTimeoutMillisForEachAttempt(3000).build();
                }
            } else {
                return RetryConfig.builder()
                                  .maxTotalAttempts(4).responseTimeoutMillisForEachAttempt(4000).build();
            }
        };
        final RetryConfigMapping mapping = RetryConfigMapping.of(keyFactory, configFactory);

        final RetryConfig config1 =
                mapping.get(context("host1", "/anypath"), HttpRequest.of(HttpMethod.GET, "/anypath"));
        final RetryConfig config2 =
                mapping.get(context("host2", "/path2"), HttpRequest.of(HttpMethod.GET, "/path2"));
        final RetryConfig config3 =
                mapping.get(context("host2", "/anypath"), HttpRequest.of(HttpMethod.GET, "/anypath"));
        final RetryConfig config4 =
                mapping.get(context("host3", "/anypath"), HttpRequest.of(HttpMethod.GET, "/anypath"));
        assertThat(config1.maxTotalAttempts()).isEqualTo(1);
        assertThat(config1.responseTimeoutMillisForEachAttempt()).isEqualTo(1000);
        assertThat(config2.maxTotalAttempts()).isEqualTo(2);
        assertThat(config2.responseTimeoutMillisForEachAttempt()).isEqualTo(2000);
        assertThat(config3.maxTotalAttempts()).isEqualTo(3);
        assertThat(config3.responseTimeoutMillisForEachAttempt()).isEqualTo(3000);
        assertThat(config4.maxTotalAttempts()).isEqualTo(4);
        assertThat(config4.responseTimeoutMillisForEachAttempt()).isEqualTo(4000);
    }

    private static ClientRequestContext context(String host, String path) {
        return ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, path))
                                   .endpoint(Endpoint.of(host))
                                   .build();
    }
}
