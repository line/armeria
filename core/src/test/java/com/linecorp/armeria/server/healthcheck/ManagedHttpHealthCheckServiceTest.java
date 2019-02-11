/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.server.healthcheck;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ServiceRequestContextBuilder;

public class ManagedHttpHealthCheckServiceTest {

    private final ManagedHttpHealthCheckService service = new ManagedHttpHealthCheckService();

    private final HttpRequest hcReq = HttpRequest.of(HttpMethod.HEAD, "/");

    private final HttpRequest hcTurnOffReq =
            HttpRequest.of(HttpMethod.PUT, "/", MediaType.PLAIN_TEXT_UTF_8, "off");
    private final HttpRequest hcTurnOnReq =
            HttpRequest.of(HttpMethod.PUT, "/", MediaType.PLAIN_TEXT_UTF_8, "on");

    @Test
    public void turnOff() throws Exception {
        service.serverHealth.setHealthy(true);

        ServiceRequestContext ctx = ServiceRequestContext.of(hcTurnOffReq);
        AggregatedHttpMessage res = service.serve(ctx, hcTurnOffReq).aggregate().get();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_TYPE))
                  .isEqualTo(MediaType.PLAIN_TEXT_UTF_8.toString());
        assertThat(res.contentUtf8()).isEqualTo("Set unhealthy.");

        ctx = ServiceRequestContextBuilder.of(hcReq).service(service).build();
        res = service.serve(ctx, hcReq).aggregate().get();

        assertThat(res.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(
                MediaType.PLAIN_TEXT_UTF_8.toString());
    }

    @Test
    public void turnOn() throws Exception {
        ServiceRequestContext ctx = ServiceRequestContext.of(hcTurnOnReq);
        AggregatedHttpMessage res = service.serve(ctx, hcTurnOnReq).aggregate().get();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(
                MediaType.PLAIN_TEXT_UTF_8.toString());
        assertThat(res.contentUtf8()).isEqualTo("Set healthy.");

        ctx = ServiceRequestContextBuilder.of(hcReq).service(service).build();
        res = service.serve(ctx, hcReq).aggregate().get();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(
                MediaType.PLAIN_TEXT_UTF_8.toString());
    }

    @Test
    public void notSupported() throws Exception {
        HttpRequestWriter noopRequest = HttpRequest.streaming(HttpMethod.PUT, "/");
        noopRequest.write(() -> HttpData.ofAscii("noop"));
        noopRequest.close();

        ServiceRequestContext ctx = ServiceRequestContext.of(noopRequest);
        AggregatedHttpMessage res = service.serve(ctx, noopRequest).aggregate().get();

        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(
                MediaType.PLAIN_TEXT_UTF_8.toString());
        assertThat(res.contentUtf8()).isEqualTo("Not supported.");

        service.serverHealth.setHealthy(true);

        noopRequest = HttpRequest.streaming(HttpMethod.PUT, "/");
        noopRequest.write(() -> HttpData.ofAscii("noop"));
        noopRequest.close();

        ctx = ServiceRequestContextBuilder.of(noopRequest).service(service).build();
        res = service.serve(ctx, noopRequest).aggregate().get();

        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(
                MediaType.PLAIN_TEXT_UTF_8.toString());
        assertThat(res.contentUtf8()).isEqualTo("Not supported.");
    }
}
