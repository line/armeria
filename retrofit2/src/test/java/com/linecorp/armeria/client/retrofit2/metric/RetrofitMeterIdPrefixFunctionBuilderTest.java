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
package com.linecorp.armeria.client.retrofit2.metric;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;

import io.micrometer.core.instrument.MeterRegistry;

class RetrofitMeterIdPrefixFunctionBuilderTest {

    @Test
    public void build_ofName() {
        final MeterRegistry registry = NoopMeterRegistry.get();
        final MeterIdPrefixFunction f =
                RetrofitMeterIdPrefixFunctionBuilder.ofName("name1")
                                                    .build();
        final ClientRequestContext ctx = newContext();
        final MeterIdPrefix res = f.apply(registry, ctx.log());
        assertThat(res.name()).isEqualTo("name1");
    }

    @Test
    public void build_ofType() {
        final MeterRegistry registry = NoopMeterRegistry.get();
        final MeterIdPrefixFunction f =
                RetrofitMeterIdPrefixFunctionBuilder.ofType("name1")
                                                    .build();
        final ClientRequestContext ctx = newContext();
        final MeterIdPrefix res = f.apply(registry, ctx.log());
        assertThat(res.name()).isEqualTo("armeria.name1");
    }

    private static ClientRequestContext newContext() {
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx.logBuilder().endRequest();
        return ctx;
    }
}
