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
package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.net.URI;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;

public class DefaultHttpClientTest {

    @SuppressWarnings("unchecked")
    @Test
    public void testConcatenateRequestPath() throws Exception {
        String clientUriPath = "http://127.0.0.1/hello";
        String requestPath = "world/test?q1=foo";

        Client<HttpRequest, HttpResponse> mockClientDelegate = mock(Client.class);
        ClientBuilderParams clientBuilderParams = new DefaultClientBuilderParams(ClientFactory.DEFAULT,
                                                                                 new URI(clientUriPath),
                                                                                 HttpClient.class,
                                                                                 ClientOptions.DEFAULT);
        DefaultHttpClient defaultHttpClient = new DefaultHttpClient(clientBuilderParams,
                                                                    mockClientDelegate,
                                                                    NoopMeterRegistry.get(),
                                                                    SessionProtocol.of("http"),
                                                                    Endpoint.of("127.0.0.1"));

        defaultHttpClient.execute(HttpRequest.of(HttpHeaders.of(HttpMethod.GET, requestPath)));

        ArgumentCaptor<HttpRequest> httpRequestArgumentCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockClientDelegate).execute(any(ClientRequestContext.class),
                                           httpRequestArgumentCaptor.capture());

        String concatPath = httpRequestArgumentCaptor.getValue().path();
        assertThat(concatPath).isEqualTo("/hello/world/test?q1=foo");
    }
}
