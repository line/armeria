/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.client.endpoint.dns;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.dns.DefaultDnsRecordEncoder;

public final class DnsNameEncoder {

    public static void encodeName(String name, ByteBuf out) {
        DefaultDnsRecordEncoderTrampoline.INSTANCE.encodeName(name, out);
    }

    private DnsNameEncoder() {}

    // Hacky trampoline class to be able to access encodeName
    private static class DefaultDnsRecordEncoderTrampoline extends DefaultDnsRecordEncoder {

        private static final DefaultDnsRecordEncoderTrampoline INSTANCE =
                new DefaultDnsRecordEncoderTrampoline();

        @Override
        protected void encodeName(String name, ByteBuf buf) {
            try {
                super.encodeName(name, buf);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
