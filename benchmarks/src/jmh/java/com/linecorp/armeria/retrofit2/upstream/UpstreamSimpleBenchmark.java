/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.retrofit2.upstream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import com.linecorp.armeria.retrofit2.shared.SimpleBenchmarkBase;
import com.linecorp.armeria.retrofit2.shared.SimpleBenchmarkClient;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

@State(Scope.Benchmark)
public class UpstreamSimpleBenchmark extends SimpleBenchmarkBase {

    @Override
    protected SimpleBenchmarkClient newClient() throws Exception {
        final SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, InsecureTrustManagerFactory.INSTANCE.getTrustManagers(), null);
        final OkHttpClient client = new OkHttpClient.Builder()
                .sslSocketFactory(context.getSocketFactory(),
                                  (X509TrustManager) InsecureTrustManagerFactory.INSTANCE.getTrustManagers()[0])
                .hostnameVerifier((s, session) -> true)
                .build();

        return new Retrofit.Builder()
                .baseUrl(baseUrl())
                .client(client)
                .addConverterFactory(JacksonConverterFactory.create())
                .build()
                .create(SimpleBenchmarkClient.class);
    }
}
