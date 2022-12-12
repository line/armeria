/*
 * Copyright 2015 LINE Corporation
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
package com.linecorp.armeria.server.thrift;

import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;

import com.linecorp.armeria.common.HttpHeaders;

import io.netty.handler.codec.http.DefaultHttpHeaders;

public class ThriftOverHttp2Test extends AbstractThriftOverHttpTest {
    @Override
    protected TTransport newTransport(String uri, HttpHeaders headers) throws TTransportException {
        final io.netty.handler.codec.http.HttpHeaders nettyDefaultHeaders = new DefaultHttpHeaders();
        headers.names().forEach(name -> nettyDefaultHeaders.set(name, headers.getAll(name)));
        return new THttp2Client(uri, nettyDefaultHeaders);
    }
}
