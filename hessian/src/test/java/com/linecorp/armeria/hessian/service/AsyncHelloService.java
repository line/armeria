/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.hessian.service;

import java.io.InputStream;
import java.util.concurrent.CompletableFuture;

/**
 * demo service.
 *
 * @author eisig
 */
public interface AsyncHelloService {

    CompletableFuture<String> sayHello();

    CompletableFuture<String> sayHelloStr(String str);

    CompletableFuture<HelloResponse> sayHello2(
            HelloRequest request);

    CompletableFuture<InputStream> replySteam(HelloRequest request);

    CompletableFuture<HelloResponse> delaySayHello(
            HelloRequest request);

    CompletableFuture<HelloResponse> failedSayHello(
            HelloRequest request);

    CompletableFuture<SequenceResponse> seq(
            SequenceRequest request);

    CompletableFuture<String> threadName();
}
