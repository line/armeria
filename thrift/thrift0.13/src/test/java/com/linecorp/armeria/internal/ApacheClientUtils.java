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

import javax.net.ssl.SSLContext;

import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.thrift.transport.THttpClient;

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
}
