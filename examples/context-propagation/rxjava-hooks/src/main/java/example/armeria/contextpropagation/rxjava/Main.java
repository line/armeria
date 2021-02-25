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

package example.armeria.contextpropagation.rxjava;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.rxjava3.RequestContextAssembly;
import com.linecorp.armeria.server.Server;

public class Main {

    public static void main(String[] args) {
        RequestContextAssembly.enable();
        final Server backend = Server.builder()
                                     .service("/square/{num}", (ctx, req) -> {
                                         final long num = Long.parseLong(ctx.pathParam("num"));
                                         return HttpResponse.of(Long.toString(num * num));
                                     })
                                     .http(8081)
                                     .build();

        final WebClient backendClient = WebClient.of("http://localhost:8081");

        final Server frontend =
                Server.builder()
                      .http(8080)
                      .serviceUnder("/", new MainService(backendClient))
                      .build();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            backend.stop().join();
            frontend.stop().join();
        }));

        backend.start().join();
        frontend.start().join();
    }
}
