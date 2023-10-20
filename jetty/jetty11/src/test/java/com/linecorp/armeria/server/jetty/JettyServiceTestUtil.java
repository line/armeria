/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.server.jetty;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.AbstractHandler;

import com.linecorp.armeria.internal.testing.SimpleChannelHandler.ThrowingBiConsumer;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

final class JettyServiceTestUtil {

    static JettyService newJettyService(ThrowingBiConsumer<Request, Response> func) {
        return JettyService.builder()
                           .handler(new AbstractHandler() {
                               @Override
                               public void handle(String target, Request baseRequest,
                                                  HttpServletRequest request,
                                                  HttpServletResponse response) throws ServletException {
                                   try {
                                       func.accept(baseRequest, (Response) response);
                                   } catch (Throwable t) {
                                       throw new ServletException(t);
                                   }
                               }
                           })
                           .build();
    }

    private JettyServiceTestUtil() {}
}
