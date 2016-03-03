/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.tracing;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Optional;

import org.junit.Test;

import com.github.kristofa.brave.Brave;

import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.RemoteInvoker;
import com.linecorp.armeria.common.tracing.HttpTracingTestBase;

import io.netty.handler.codec.http.HttpHeaders;

public class HttpTracingRemoteInvokerTest extends HttpTracingTestBase {

    private static final HttpTracingRemoteInvoker remoteInvoker =
            new HttpTracingRemoteInvoker(mock(RemoteInvoker.class), mock(Brave.class));

    @Test
    public void testPutTraceData() {
        HttpHeaders baseHeaders = otherHeaders();
        ClientOptions baseOptions = ClientOptions.of(ClientOption.HTTP_HEADERS.newValue(baseHeaders));

        ClientOptions newOptions = remoteInvoker.putTraceData(baseOptions, testSpanId);

        HttpHeaders expectedHeaders = traceHeaders().add(otherHeaders());
        assertThat(newOptions.get(ClientOption.HTTP_HEADERS), is(Optional.of(expectedHeaders)));
    }

    @Test
    public void testPutTraceDataIfSpanIsNull() {
        HttpHeaders baseHeaders = otherHeaders();
        ClientOptions baseOptions = ClientOptions.of(ClientOption.HTTP_HEADERS.newValue(baseHeaders));

        ClientOptions newOptions = remoteInvoker.putTraceData(baseOptions, null);

        HttpHeaders expectedHeaders = traceHeadersNotSampled().add(otherHeaders());
        assertThat(newOptions.get(ClientOption.HTTP_HEADERS), is(Optional.of(expectedHeaders)));
    }
}
