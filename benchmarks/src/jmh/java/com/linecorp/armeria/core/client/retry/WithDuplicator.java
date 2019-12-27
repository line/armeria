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

package com.linecorp.armeria.core.client.retry;

import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.retry.RetryStrategyWithContent;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.HttpResponse;

@State(Scope.Benchmark)
public class WithDuplicator extends RetryingClientBase {

    @Override
    protected WebClient newClient() {
        final RetryStrategyWithContent<HttpResponse> retryStrategy =
                (ctx, response) -> response.aggregate().handle((unused1, unused2) -> null);

        return WebClient.builder(baseUrl())
                        .decorator(RetryingClient.builder(retryStrategy)
                                                 .newDecorator())
                        .build();
    }
}
