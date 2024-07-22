/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.internal;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.Map.Entry;

import javax.net.ssl.SSLContext;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.util.EntityUtils;
import org.apache.thrift.transport.THttpClient;

import com.linecorp.armeria.common.HttpMethod;

public final class ApacheClientUtils {

    private ApacheClientUtils() {}

    public static THttpClient allTrustClient(String uri) {
        try {
            final SSLContext sslContext =
                    SSLContextBuilder.create()
                                     .loadTrustMaterial((TrustStrategy) (chain, authType) -> true)
                                     .build();
            return new THttpClient(
                    uri, HttpClientBuilder.create()
                                          .setSSLHostnameVerifier((hostname, session) -> true)
                                          .setSSLContext(sslContext)
                                          .build());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Entry<String, String> makeApacheHttpRequest(String uri, HttpMethod method) {
        final HttpUriRequest req;
        switch (method) {
            case GET:
                req = new HttpGet(uri);
                break;
            case DELETE:
                req = new HttpDelete(uri);
                break;
            case POST:
                req = new HttpPost(uri);
                break;
            default:
                throw new UnsupportedOperationException("Unsupported HTTP method: " + method);
        }
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(req)) {
                return new AbstractMap.SimpleEntry<>(res.getProtocolVersion().toString(),
                                                     EntityUtils.toString(res.getEntity()));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
