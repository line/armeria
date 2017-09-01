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

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.when;

import org.apache.http.HttpHeaders;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.DefaultHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.logging.DefaultRequestLog;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.AsciiString;

public class ManagedHttpHealthCheckServiceTest {

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    private static final DefaultHttpRequest HC_REQ = new DefaultHttpRequest(HttpMethod.HEAD, "/");

    private static final DefaultHttpRequest HC_TURN_OFF_REQ = new DefaultHttpRequest(HttpMethod.PUT, "/");
    private static final DefaultHttpRequest HC_TURN_ON_REQ = new DefaultHttpRequest(HttpMethod.PUT, "/");

    @Mock
    private ServiceRequestContext context;

    private final ManagedHttpHealthCheckService service = new ManagedHttpHealthCheckService();

    @Before
    public void setUp() {
        when(context.logBuilder()).thenReturn(new DefaultRequestLog(context));

        HC_TURN_OFF_REQ.write(HttpData.ofAscii("off"));
        HC_TURN_OFF_REQ.close();

        HC_TURN_ON_REQ.write(HttpData.ofAscii("on"));
        HC_TURN_ON_REQ.close();
    }

    @Test
    public void turnOff() throws Exception {
        service.serverHealth.setHealthy(true);

        AggregatedHttpMessage res = service.serve(context, HC_TURN_OFF_REQ).aggregate().get();

        assertThat(res.status(), is(HttpStatus.OK));
        assertThat(res.headers().get(AsciiString.of(HttpHeaders.CONTENT_TYPE)),
                   is(MediaType.PLAIN_TEXT_UTF_8.toString()));
        assertThat(res.content().toStringUtf8(), is("Set unhealthy."));

        res = service.serve(context, HC_REQ).aggregate().get();

        assertThat(res.status(), is(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(res.headers().get(AsciiString.of(HttpHeaders.CONTENT_TYPE)),
                   is(MediaType.PLAIN_TEXT_UTF_8.toString()));
    }

    @Test
    public void turnOn() throws Exception {
        AggregatedHttpMessage res = service.serve(context, HC_TURN_ON_REQ).aggregate().get();

        assertThat(res.status(), is(HttpStatus.OK));
        assertThat(res.headers().get(AsciiString.of(HttpHeaders.CONTENT_TYPE)),
                   is(MediaType.PLAIN_TEXT_UTF_8.toString()));
        assertThat(res.content().toStringUtf8(), is("Set healthy."));

        res = service.serve(context, HC_REQ).aggregate().get();

        assertThat(res.status(), is(HttpStatus.OK));
        assertThat(res.headers().get(AsciiString.of(HttpHeaders.CONTENT_TYPE)),
                   is(MediaType.PLAIN_TEXT_UTF_8.toString()));
    }

    @Test
    public void notSupported() throws Exception {
        DefaultHttpRequest noopRequest = new DefaultHttpRequest(HttpMethod.PUT, "/");
        noopRequest.write(() -> HttpData.ofAscii("noop"));
        noopRequest.close();

        AggregatedHttpMessage res = service.serve(context, noopRequest).aggregate().get();

        assertThat(res.status(), is(HttpStatus.BAD_REQUEST));
        assertThat(res.headers().get(AsciiString.of(HttpHeaders.CONTENT_TYPE)),
                   is(MediaType.PLAIN_TEXT_UTF_8.toString()));
        assertThat(res.content().toStringUtf8(), is("Not supported."));

        service.serverHealth.setHealthy(true);

        noopRequest = new DefaultHttpRequest(HttpMethod.PUT, "/");
        noopRequest.write(() -> HttpData.ofAscii("noop"));
        noopRequest.close();

        res = service.serve(context, noopRequest).aggregate().get();

        assertThat(res.status(), is(HttpStatus.BAD_REQUEST));
        assertThat(res.headers().get(AsciiString.of(HttpHeaders.CONTENT_TYPE)),
                   is(MediaType.PLAIN_TEXT_UTF_8.toString()));
        assertThat(res.content().toStringUtf8(), is("Not supported."));
    }
}
