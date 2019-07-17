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

package com.linecorp.armeria.server.brave;

import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.logging.RequestLog;

import brave.SpanCustomizer;
import brave.http.HttpAdapter;
import brave.http.HttpServerParser;

/**
 * Default implementation of {@link HttpServerParser}.
 * This parser add some custom tags and overwrite the name of span if {@link RequestLog#requestContent()}
 * is {@link RpcRequest}.
 * The following tags become available:
 * <ul>
 *   <li>http.url</li>
 *   <li>http.host</li>
 *   <li>http.protocol</li>
 *   <li>http.serfmt</li>
 *   <li>address.remote</li>
 *   <li>address.local</li>
 * </ul>
 * User can extend this class or implement own {@link HttpServerParser}.
 */
public class ArmeriaHttpServerParser extends HttpServerParser {
    @Override
    public <T> void response(HttpAdapter<?, T> rawAdapter, T res, Throwable error, SpanCustomizer customizer) {
        super.response(rawAdapter, res, error, customizer);
        if (res instanceof RequestLog && rawAdapter instanceof ArmeriaHttpServerAdapter) {
            final RequestLog requestLog = (RequestLog) res;
            final ArmeriaHttpServerAdapter adapter = (ArmeriaHttpServerAdapter) rawAdapter;
            customizer.tag("http.host", adapter.authority(requestLog))
                      .tag("http.url", adapter.url(requestLog))
                      .tag("http.protocol", adapter.protocol(requestLog));

            final String serFmt = adapter.serializationFormat(requestLog);
            if (serFmt != null) {
                customizer.tag("http.serfmt", serFmt);
            }

            final String raddr = adapter.remoteAddress(requestLog);
            if (raddr != null) {
                customizer.tag("address.remote", raddr);
            }

            final String laddr = adapter.localAddress(requestLog);
            if (laddr != null) {
                customizer.tag("address.local", laddr);
            }

            final String rpcMethod = adapter.rpcMethod(requestLog);
            if (rpcMethod != null) {
                customizer.name(rpcMethod);
            }
        }
    }
}
