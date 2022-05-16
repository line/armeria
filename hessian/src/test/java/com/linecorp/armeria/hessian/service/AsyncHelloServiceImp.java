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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.util.UnmodifiableFuture;

/**
 * async demo.
 *
 * @author eisig
 */
public class AsyncHelloServiceImp implements AsyncHelloService {
    private static final Logger log = LoggerFactory.getLogger(AsyncHelloServiceImp.class);

    private final ConcurrentMap<String, Integer> seqMap = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<String> sayHello() {
        return UnmodifiableFuture.completedFuture("Hello");
    }

    @Override
    public CompletableFuture<String> sayHelloStr(String str) {
        return UnmodifiableFuture.completedFuture("Hello " + str);
    }

    @Override
    public CompletableFuture<HelloResponse> sayHello2(
            HelloRequest request) {
        return UnmodifiableFuture.completedFuture(new HelloResponse("Hello " + request.getMessage()));
    }

    @Override
    public CompletableFuture<InputStream> replySteam(HelloRequest request) {
        final String result = "Hello " + request.getMessage();
        return UnmodifiableFuture.completedFuture(
                new ByteArrayInputStream(result.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public CompletableFuture<HelloResponse> delaySayHello(
            HelloRequest request) {
        log.info("Receive delay sayHello");
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ex) {
            log.error("interrupted", ex);
        }
        return UnmodifiableFuture.completedFuture(new HelloResponse("DelaySayHello " + request.getMessage()));
    }

    @Override
    public CompletableFuture<HelloResponse> failedSayHello(
            HelloRequest request) {
        throw new DemoException("failed");
    }

    @Override
    public CompletableFuture<SequenceResponse> seq(SequenceRequest request) {
        final Integer v = seqMap.compute(request.getName(), (s, integer) -> integer != null ? integer + 1 : 1);
        log.info("seq: {} -> {}", request.getName(), v);
        return UnmodifiableFuture.completedFuture(new SequenceResponse(v));
    }

    @Override
    public CompletableFuture<String> threadName() {
        return UnmodifiableFuture.completedFuture(Thread.currentThread().getName());
    }
}
