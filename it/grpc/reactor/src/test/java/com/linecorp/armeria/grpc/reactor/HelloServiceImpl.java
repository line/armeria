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

package com.linecorp.armeria.grpc.reactor;

import java.time.Duration;

import com.linecorp.armeria.common.grpc.GrpcStatusFunction;
import com.linecorp.armeria.grpc.reactor.Hello.HelloReply;
import com.linecorp.armeria.grpc.reactor.Hello.HelloRequest;
import com.linecorp.armeria.grpc.reactor.ReactorHelloServiceGrpc.HelloServiceImplBase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

public class HelloServiceImpl extends HelloServiceImplBase {

    /**
     * Sends 5 {@link HelloReply} responses published without a specific {@link Scheduler}
     * when receiving a request.
     */
    @Override
    public Flux<HelloReply> lotsOfRepliesWithoutScheduler(Mono<HelloRequest> request) {
        return request
                .flatMapMany(
                        it -> Flux.interval(Duration.ofSeconds(1))
                                  .take(5)
                                  .map(index -> "Hello, " + it.getName() + "! (sequence: " + (index + 1) + ')')
                )
                .map(HelloServiceImpl::buildReply);
    }

    /**
     * Throws an {@link AuthError}, and the exception will be handled by {@link GrpcStatusFunction}.
     */
    @Override
    public Mono<HelloReply> helloError(Mono<HelloRequest> request) {
        return request.map(req -> {
            throw new AuthError(req.getName() + " is unauthenticated");
        });
    }

    private static HelloReply buildReply(Object message) {
        return HelloReply.newBuilder().setMessage(String.valueOf(message)).build();
    }

    public static class AuthError extends RuntimeException {

        public AuthError(String message) {
            super(message);
        }
    }
}
