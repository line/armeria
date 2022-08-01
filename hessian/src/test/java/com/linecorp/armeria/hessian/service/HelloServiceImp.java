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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bytebuddy.utility.RandomString;

/**
 * demo.
 * @author eisig
 */
public class HelloServiceImp implements HelloService {
    private static final Logger log = LoggerFactory.getLogger(HelloServiceImp.class);

    private final ConcurrentMap<String, Integer> seqMap = new ConcurrentHashMap<>();

    @Override
    public String sayHello() {
        return "Hello";
    }

    @Override
    public String sayHelloStr(String str) {
        return "Hello " + str;
    }

    @Override
    public HelloResponse sayHello2(
            HelloRequest request) {
        return new HelloResponse("Hello " + request.getMessage());
    }

    @Override
    public InputStream replySteam(HelloRequest request) {
        final String result = "Hello " + request.getMessage();
        return new ByteArrayInputStream(result.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public HelloResponse delaySayHello(
            HelloRequest request) {
        log.info("Receive delay sayHello");
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ex) {
            log.error("interrupted", ex);
        }
        return new HelloResponse("DelaySayHello " + request.getMessage());
    }

    @Override
    public HelloResponse failedSayHello(
            HelloRequest request) {
        throw new DemoException("failed");
    }

    @Override
    public SequenceResponse seq(
            SequenceRequest request) {
        final Integer v = seqMap.compute(request.getName(), (s, integer) -> integer != null ? integer + 1 : 1);
        log.info("seq: {} -> {}", request.getName(), v);
        return new SequenceResponse(v);
    }

    private final RandomString randomString = new RandomString(10240);

    @Override
    public HelloResponse largeResponse(
            HelloRequest request) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append(randomString.nextString());
        }
        return new HelloResponse(sb.toString());
    }

    @Override
    public String threadName() {
        return Thread.currentThread().getName();
    }
}
