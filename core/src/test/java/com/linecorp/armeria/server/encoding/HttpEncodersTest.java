/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.encoding;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.Mockito.when;

import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;

public class HttpEncodersTest {
    @Rule public MockitoRule mocks = MockitoJUnit.rule();

    @Mock private HttpRequest request;

    @Test
    public void noAcceptEncoding() {
        when(request.headers()).thenReturn(RequestHeaders.of(HttpMethod.GET, "/"));
        assertThat(HttpEncoders.getWrapperForRequest(request)).isNull();
    }

    @Test
    public void acceptEncodingGzip() {
        when(request.headers()).thenReturn(RequestHeaders.of(HttpMethod.GET, "/",
                                                             HttpHeaderNames.ACCEPT_ENCODING, "gzip"));
        assertThat(HttpEncoders.getWrapperForRequest(request)).isEqualTo(HttpEncodingType.GZIP);
    }

    @Test
    public void acceptEncodingDeflate() {
        when(request.headers()).thenReturn(RequestHeaders.of(HttpMethod.GET, "/",
                                                             HttpHeaderNames.ACCEPT_ENCODING, "deflate"));
        assertThat(HttpEncoders.getWrapperForRequest(request)).isEqualTo(HttpEncodingType.DEFLATE);
    }

    @Test
    public void acceptEncodingBrotli() {
        when(request.headers()).thenReturn(RequestHeaders.of(HttpMethod.GET, "/",
                                                             HttpHeaderNames.ACCEPT_ENCODING, "br"));
        assertThat(HttpEncoders.getWrapperForRequest(request)).isEqualTo(HttpEncodingType.BR);
    }

    @Test
    public void acceptEncodingAllOfThree() {
        when(request.headers()).thenReturn(RequestHeaders.of(HttpMethod.GET, "/",
                                                             HttpHeaderNames.ACCEPT_ENCODING,
                                                             "gzip, deflate, br"));
        assertThat(HttpEncoders.getWrapperForRequest(request)).isEqualTo(HttpEncodingType.GZIP);
    }

    @Test
    public void acceptEncodingBoth() {
        when(request.headers()).thenReturn(RequestHeaders.of(HttpMethod.GET, "/",
                                                             HttpHeaderNames.ACCEPT_ENCODING, "gzip, deflate"));
        assertThat(HttpEncoders.getWrapperForRequest(request)).isEqualTo(HttpEncodingType.GZIP);
    }

    @Test
    public void acceptEncodingUnknown() {
        when(request.headers()).thenReturn(RequestHeaders.of(HttpMethod.GET, "/",
                                                             HttpHeaderNames.ACCEPT_ENCODING, "piedpiper"));
        assertThat(HttpEncoders.getWrapperForRequest(request)).isNull();
    }

    @Test
    public void acceptEncodingWithQualityValues() {
        when(request.headers()).thenReturn(RequestHeaders.of(HttpMethod.GET, "/",
                                                             HttpHeaderNames.ACCEPT_ENCODING,
                                                             "br;q=0.8, deflate, gzip;q=0.7, *;q=0.1"));
        assertThat(HttpEncoders.getWrapperForRequest(request)).isEqualTo(HttpEncodingType.DEFLATE);
    }

    @Test
    public void acceptEncodingOrder() {
        when(request.headers()).thenReturn(RequestHeaders.of(HttpMethod.GET, "/",
                                                             HttpHeaderNames.ACCEPT_ENCODING,
                                                             "deflate;q=0.5, gzip;q=0.9, br;q=0.9"));
        assertThat(HttpEncoders.getWrapperForRequest(request)).isEqualTo(HttpEncodingType.GZIP);
    }

    @Test
    public void acceptEncodingWithZeroValues() {
        when(request.headers()).thenReturn(RequestHeaders.of(HttpMethod.GET, "/",
                                                             HttpHeaderNames.ACCEPT_ENCODING,
                                                             "gzip;q=0.0, br;q=0.0, *;q=0.1"));
        assertThat(HttpEncoders.getWrapperForRequest(request)).isEqualTo(HttpEncodingType.DEFLATE);
    }
}
