/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.armeria.it.xds;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerConfigurator;
import com.linecorp.armeria.server.logging.LoggingService;

/**
 * Simple echo {@link ServerConfigurator} shared across Istio integration tests.
 * Responds with {@code "hello"} on {@code GET /echo}.
 */
public final class EchoConfigurator implements ServerConfigurator {
    @Override
    public void reconfigure(ServerBuilder sb) {
        sb.service("/echo", (ctx, req) -> HttpResponse.of("hello"));
        sb.decorator(LoggingService.newDecorator());
    }
}
