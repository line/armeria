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

import java.util.AbstractMap;
import java.util.Map.Entry;

import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.thrift.transport.THttpClient;

import com.linecorp.armeria.common.HttpMethod;

public final class ApacheClientUtils {

    private ApacheClientUtils() {}

    public static THttpClient allTrustClient(String uri) {
        CloseableHttpClient httpclient = null;
        try {
            final PoolingHttpClientConnectionManager connManager = PoolingHttpClientConnectionManagerBuilder
                    .create()
                    .setSSLSocketFactory(
                            SSLConnectionSocketFactoryBuilder
                                    .create()
                                    .setSslContext(
                                            SSLContextBuilder.create()
                                                             .loadTrustMaterial(
                                                                     TrustAllStrategy.INSTANCE)
                                                             .build())
                                    .setHostnameVerifier(
                                            NoopHostnameVerifier.INSTANCE)
                                    .build())
                    .build();
            httpclient = HttpClients
                    .custom()
                    .setConnectionManager(connManager)
                    .build();
            return new THttpClient(uri, httpclient);
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
                return new AbstractMap.SimpleEntry<>(res.getVersion().toString(),
                                                     EntityUtils.toString(res.getEntity()));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
