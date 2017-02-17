/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.client.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DefaultClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpRequest;

import io.netty.channel.DefaultEventLoop;

public class HttpClientDelegateTest {
    @Test
    public void givenDifferentPathInContextAndHttpRequest_whenExecute_thenSetsPathInContextToRequestHeader()
            throws Exception {
        //given
        HttpHeaders httpHeaders = HttpHeaders.of(HttpMethod.GET, "/world");
        HttpRequest request = HttpRequest.of(httpHeaders);
        ClientRequestContext context = new DefaultClientRequestContext(new DefaultEventLoop(),
                                                                       SessionProtocol.of("http"),
                                                                       Endpoint.of("127.0.0.1"), "GET",
                                                                       "/hello/world", "",
                                                                       ClientOptions.DEFAULT, request);
        //when
        HttpClientDelegate httpClientDelegate = new HttpClientDelegate(new HttpClientFactory());
        httpClientDelegate.execute(context, request);
        //then
        assertThat(httpHeaders.path()).isEqualTo("/hello/world");
    }
}
