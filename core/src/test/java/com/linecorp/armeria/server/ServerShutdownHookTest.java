/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.server;

import com.linecorp.armeria.common.HttpResponse;

/**
 * For checking the behavior of {@link Server#closeOnJvmShutdown()}.
 * This app must output the following text:
 * <pre>{@code
 * - Before stopping Server
 * - Server has been closed.
 * - After Server stopped
 * }</pre>
 */
@SuppressWarnings({ "checkstyle:HideUtilityClassConstructor", "checkstyle:UncommentedMain" })
public final class ServerShutdownHookTest {

    public static void main(String[] args) {
        final Server server = Server.builder()
                                    .service("/", (ctx, res) -> HttpResponse.of(""))
                                    .build();
        final Runnable whenClosing = () -> System.out.println("Before stopping Server");

        server.closeOnJvmShutdown(whenClosing).thenRun(() -> {
            System.out.println("After Server stopped");
        });
        server.start().join();
        System.exit(0);
    }
}
