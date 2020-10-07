/*
 * Copyright 2020 LINE Corporation
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
package com.example.helloworld;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import akka.NotUsed;
import akka.actor.typed.ActorSystem;
import akka.japi.Pair;
import akka.stream.javadsl.BroadcastHub;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.MergeHub;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;

// Forked from https://github.com/akka/akka-grpc-quickstart-java.g8/blob/master
// /src/main/g8/src/main/java/com/example/helloworld/GreeterServiceImpl.java
public final class GreeterServiceImpl implements GreeterService {

    private final ActorSystem<?> system;
    private final Sink<HelloRequest, NotUsed> inboundHub;
    private final Source<HelloReply, NotUsed> outboundHub;

    public GreeterServiceImpl(ActorSystem<?> system) {
        this.system = system;
        final Pair<Sink<HelloRequest, NotUsed>, Source<HelloReply, NotUsed>> hubInAndOut =
                MergeHub.of(HelloRequest.class)
                        .map(request -> HelloReply.newBuilder()
                                                  .setMessage("Hello, " + request.getName())
                                                  .build())
                        .toMat(BroadcastHub.of(HelloReply.class), Keep.both())
                        .run(system);

        inboundHub = hubInAndOut.first();
        outboundHub = hubInAndOut.second();
    }

    @Override
    public CompletionStage<HelloReply> sayHello(HelloRequest request) {
        return CompletableFuture.completedFuture(
                HelloReply.newBuilder()
                          .setMessage("Hello, " + request.getName())
                          .build()
        );
    }

    @Override
    public Source<HelloReply, NotUsed> sayHelloToAll(Source<HelloRequest, NotUsed> in) {
        in.runWith(inboundHub, system);
        return outboundHub;
    }
}
