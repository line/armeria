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

package com.linecorp.armeria.retrofit2.downstream;

import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.retrofit2.ArmeriaRetrofit;
import com.linecorp.armeria.retrofit2.shared.SimpleBenchmarkBase;
import com.linecorp.armeria.retrofit2.shared.SimpleBenchmarkClient;

import retrofit2.converter.jackson.JacksonConverterFactory;

@State(Scope.Benchmark)
public class DownstreamSimpleBenchmark extends SimpleBenchmarkBase {

    @Override
    protected SimpleBenchmarkClient newClient() {
        final WebClient webClient = WebClient.builder(baseUrl())
                                             .factory(ClientFactory.insecure())
                                             .build();
        return ArmeriaRetrofit.builder(webClient)
                              .addConverterFactory(JacksonConverterFactory.create())
                              .build()
                              .create(SimpleBenchmarkClient.class);
    }
}
