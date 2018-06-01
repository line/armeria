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

package com.linecorp.armeria.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.testing.internal.AnticipatedException;

import io.netty.channel.Channel;
import io.netty.util.AsciiString;

public class DefaultRequestLogTest {

    @Rule
    public MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    private RequestContext ctx;

    @Mock
    private Channel channel;

    private DefaultRequestLog log;

    @Before
    public void setUp() {
        log = new DefaultRequestLog(ctx);
    }

    @Test
    public void endRequestSuccess() {
        when(ctx.sessionProtocol()).thenReturn(SessionProtocol.H2C);
        log.endRequest();
        assertThat(log.requestDurationNanos()).isZero();
        assertThat(log.requestCause()).isNull();
    }

    @Test
    public void endRequestWithoutHeaders() {
        when(ctx.sessionProtocol()).thenReturn(SessionProtocol.H2C);
        log.endRequest();
        final HttpHeaders headers = log.requestHeaders();
        assertThat(headers.scheme()).isEqualTo("http");
        assertThat(headers.authority()).isEqualTo("?");
        assertThat(headers.method()).isSameAs(HttpMethod.UNKNOWN);
        assertThat(headers.path()).isEqualTo("?");
    }

    @Test
    public void endResponseSuccess() {
        when(ctx.sessionProtocol()).thenReturn(SessionProtocol.H2C);
        log.endResponse();
        assertThat(log.responseDurationNanos()).isZero();
        assertThat(log.responseCause()).isNull();
    }

    @Test
    public void endResponseFailure() {
        final Throwable error = new Throwable("response failed");
        log.endResponse(error);
        assertThat(log.responseDurationNanos()).isZero();
        assertThat(log.responseCause()).isSameAs(error);
    }

    @Test
    public void endResponseWithoutHeaders() {
        log.endResponse();
        assertThat(log.responseHeaders().status()).isEqualTo(HttpStatus.valueOf(0));
    }

    @Test
    public void rpcFailure_endResponseWithoutCause() {
        final Throwable error = new Throwable("response failed");
        log.responseContent(RpcResponse.ofFailure(error), null);
        // If user code doesn't call endResponse, the framework automatically does with no cause.
        log.endResponse();
        assertThat(log.responseDurationNanos()).isZero();
        assertThat(log.responseCause()).isSameAs(error);
    }

    @Test
    public void rpcFailure_endResponseDifferentCause() {
        final Throwable error = new Throwable("response failed one way");
        final Throwable error2 = new Throwable("response failed a different way?");
        log.responseContent(RpcResponse.ofFailure(error), null);
        log.endResponse(error2);
        assertThat(log.responseDurationNanos()).isZero();
        assertThat(log.responseCause()).isSameAs(error);
    }

    @Test
    public void addChild() {
        final DefaultRequestLog child = new DefaultRequestLog(ctx);
        log.addChild(child);
        child.startRequest(channel, SessionProtocol.H2C);
        assertThat(log.requestStartTimeMillis()).isEqualTo(child.requestStartTimeMillis());
        assertThat(log.channel()).isSameAs(channel);
        assertThat(log.sessionProtocol()).isSameAs(SessionProtocol.H2C);

        child.serializationFormat(SerializationFormat.NONE);
        assertThat(log.serializationFormat()).isSameAs(SerializationFormat.NONE);

        final HttpHeaders foo = HttpHeaders.of(AsciiString.of("foo"), "foo");
        child.requestHeaders(foo);
        assertThat(log.requestHeaders()).isSameAs(foo);

        final String requestContent = "baz";
        final String rawRequestContent = "bax";

        child.requestContent(requestContent, rawRequestContent);
        assertThat(log.requestContent()).isSameAs(requestContent);
        assertThat(log.rawRequestContent()).isSameAs(rawRequestContent);

        child.endRequest();
        assertThat(log.requestDurationNanos()).isEqualTo(child.requestDurationNanos());

        // response-side log are propagated when RequestLogBuilder.endResponseWithLastChild() is invoked
        child.startResponse();
        assertThatThrownBy(() -> log.responseStartTimeMillis())
                .isExactlyInstanceOf(RequestLogAvailabilityException.class);

        final HttpHeaders bar = HttpHeaders.of(AsciiString.of("bar"), "bar");
        child.responseHeaders(bar);
        assertThatThrownBy(() -> log.responseHeaders())
                .isExactlyInstanceOf(RequestLogAvailabilityException.class);

        log.endResponseWithLastChild();
        assertThat(log.responseStartTimeMillis()).isEqualTo(child.responseStartTimeMillis());
        assertThat(log.responseHeaders()).isSameAs(bar);

        final String responseContent = "baz1";
        final String rawResponseContent = "bax1";
        child.responseContent(responseContent, rawResponseContent);
        assertThat(log.responseContent()).isSameAs(responseContent);
        assertThat(log.rawResponseContent()).isSameAs(rawResponseContent);

        child.endResponse(new AnticipatedException("Oops!"));
        assertThat(log.responseDurationNanos()).isEqualTo(child.responseDurationNanos());
        assertThat(log.totalDurationNanos()).isEqualTo(child.totalDurationNanos());
    }
}
