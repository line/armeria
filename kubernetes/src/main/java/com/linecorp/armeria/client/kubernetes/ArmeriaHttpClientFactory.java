/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.client.kubernetes;

import com.linecorp.armeria.client.WebClientBuilder;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.http.HttpClient;
import io.fabric8.kubernetes.client.http.HttpClient.Factory;

/**
 * An {@link HttpClient.Factory} for creating {@link ArmeriaHttpClient}.
 */
public class ArmeriaHttpClientFactory implements HttpClient.Factory {
    @Override
    public ArmeriaHttpClientBuilder newBuilder() {
        return new ArmeriaHttpClientBuilder(this);
    }

    @Override
    public ArmeriaHttpClientBuilder newBuilder(Config config) {
        return (ArmeriaHttpClientBuilder) Factory.super.newBuilder(config);
    }

    /**
     * Subclasses may use this to apply additional configuration after the Config has been applied
     * This method is only called for clients constructed using the Config.
     */
    protected void additionalConfig(WebClientBuilder builder) {
        // no default implementation
    }
}
