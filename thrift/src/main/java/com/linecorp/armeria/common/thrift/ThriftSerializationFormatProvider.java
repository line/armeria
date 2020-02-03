/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common.thrift;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Set;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SerializationFormatProvider;
import com.linecorp.armeria.common.util.UnstableApi;

/**
 * {@link SerializationFormatProvider} that provides the Thrift-related {@link SerializationFormat}s.
 */
@UnstableApi
public final class ThriftSerializationFormatProvider extends SerializationFormatProvider {
    @Override
    protected Set<Entry> entries() {
        return ImmutableSet.of(
                new Entry("tbinary",
                          create("x-thrift", "TBINARY"),
                          create("vnd.apache.thrift.binary")),
                new Entry("tcompact",
                          create("x-thrift", "TCOMPACT"),
                          create("vnd.apache.thrift.compact")),
                new Entry("tjson",
                          create("x-thrift", "TJSON"),
                          create("x-thrift", "TJSON").withCharset(UTF_8),
                          create("vnd.apache.thrift.json"),
                          create("vnd.apache.thrift.json").withCharset(UTF_8)),
                new Entry("ttext",
                          create("x-thrift", "TTEXT"),
                          create("x-thrift", "TTEXT").withCharset(UTF_8),
                          create("vnd.apache.thrift.text"),
                          create("vnd.apache.thrift.text").withCharset(UTF_8)));
    }

    private static MediaType create(String subtype) {
        return MediaType.create("application", subtype);
    }

    private static MediaType create(String subtype, String protocol) {
        return create(subtype).withParameter("protocol", protocol);
    }
}
