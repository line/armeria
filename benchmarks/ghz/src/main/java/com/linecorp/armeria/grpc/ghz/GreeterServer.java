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

package com.linecorp.armeria.grpc.ghz;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.util.Version;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.grpc.GrpcService;

import io.grpc.stub.StreamObserver;

/**
 * A gRPC server which is used for measuring performance with https://github.com/bojand/ghz.
 * Use {@code bench.sh} to load this server.
 */
final class GreeterServer {

    public static void main(String[] args) throws IOException, InterruptedException {
        /* The port on which the server should run */
        int port;
        try {
            port = Integer.parseInt(System.getenv("GRPC_SERVER_PORT"));
        } catch (NumberFormatException e) {
            // use default port
            port = 50051;
        }

        final boolean useBlockingTaskExecutor =
                Boolean.parseBoolean(System.getenv("GRPC_SERVER_USE_BLOCKING_EXECUTOR"));

        final Server server =
                Server.builder()
                      .http(port)
                      .service(GrpcService.builder()
                                          .addService(new GreeterImpl())
                                          .supportedSerializationFormats(GrpcSerializationFormats.PROTO)
                                          .useBlockingTaskExecutor(useBlockingTaskExecutor)
                                          .build())
                      .disableDateHeader()
                      .disableServerHeader()
                      .build();
        server.start().join();
        final Version armeria = Version.get("armeria");
        System.err.println(armeria + " Server started, listening on " + port);
        server.closeOnShutdown(() -> {
            // Use stderr here since the logger may have been
            // reset by its JVM shutdown hook.
            System.err.println("*** shutting down gRPC server since JVM is shutting down");
            try {
                server.stop().get(30, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                e.printStackTrace();
            }
            System.err.println("*** server shut down");
        });
    }

    private static final class GreeterImpl extends GreeterGrpc.GreeterImplBase {
        @Override
        public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
            final HelloReply reply = HelloReply.newBuilder().setMessage(req.getName()).build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }
    }
}
