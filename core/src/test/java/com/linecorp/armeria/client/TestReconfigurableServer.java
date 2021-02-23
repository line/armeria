/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;

class TestReconfigurableServer {

    public static void main(String args[]) {
        System.out.println("Hello....");
    }
    @Test
    public void test() throws InterruptedException, ExecutionException {
        final ServerBuilder sb = Server.builder();
        sb.http(8080);

        sb.service("/hello", (ctx, req) -> {
            return HttpResponse.of("Hello, world!");
        });

        sb.service("/Bye", (ctx, req) -> {
            return HttpResponse.of("Bye, world!");
        });

        final Server server = sb.build();

        final CompletableFuture<Void> future = server.start();
        server.reconfigure(serverBuilder  -> {
            // Replace the entire routes with the following two services.
            serverBuilder.service("/hello_v2", (ctx, req) -> {
                return HttpResponse.of("Hello, world!");
            });
            serverBuilder.service("/bye_v2", (ctx, req) -> {
                return HttpResponse.of("bye, world!");
            });
            return serverBuilder;
        });
        future.join();

        while (true) {
            Thread.sleep(1000);
        }
    }

}
