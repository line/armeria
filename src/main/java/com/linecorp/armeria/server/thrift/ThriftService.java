/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.thrift;

import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.thrift.ThriftReply;
import com.linecorp.armeria.server.Service;

/**
 * A {@link Service} that handles a Thrift call.
 *
 * @deprecated Use {@link THttpService} instead.
 */
@Deprecated
public class ThriftService extends THttpService {

    /**
     * @deprecated Use {@link THttpService#of(Object)} instead.
     */
    @Deprecated
    public static ThriftService of(Object implementation) {
        return of(implementation, SerializationFormat.THRIFT_BINARY);
    }

    /**
     * @deprecated Use {@link THttpService#of(Object, SerializationFormat)} instead.
     */
    @Deprecated
    public static ThriftService of(Object implementation,
                                   SerializationFormat defaultSerializationFormat) {

        return new ThriftService(ThriftCallService.of(implementation),
                                 defaultSerializationFormat, SerializationFormat.ofThrift());
    }

    /**
     * @deprecated Use {@link THttpService#ofFormats(Object, SerializationFormat, SerializationFormat...)}
     *             instead.
     */
    @Deprecated
    public static ThriftService ofFormats(
            Object implementation,
            SerializationFormat defaultSerializationFormat,
            SerializationFormat... otherAllowedSerializationFormats) {

        requireNonNull(otherAllowedSerializationFormats, "otherAllowedSerializationFormats");
        return ofFormats(implementation,
                         defaultSerializationFormat,
                         Arrays.asList(otherAllowedSerializationFormats));
    }

    /**
     * @deprecated Use {@link THttpService#ofFormats(Object, SerializationFormat, Iterable)} instead.
     */
    @Deprecated
    public static ThriftService ofFormats(
            Object implementation,
            SerializationFormat defaultSerializationFormat,
            Iterable<SerializationFormat> otherAllowedSerializationFormats) {

        requireNonNull(otherAllowedSerializationFormats, "otherAllowedSerializationFormats");
        EnumSet<SerializationFormat> allowedSerializationFormatsSet = EnumSet.of(defaultSerializationFormat);
        otherAllowedSerializationFormats.forEach(allowedSerializationFormatsSet::add);

        return new ThriftService(ThriftCallService.of(implementation),
                                 defaultSerializationFormat, allowedSerializationFormatsSet);
    }

    private ThriftService(Service<ThriftCall, ThriftReply> delegate,
                          SerializationFormat defaultSerializationFormat,
                          Set<SerializationFormat> allowedSerializationFormats) {
        super(delegate, defaultSerializationFormat, allowedSerializationFormats);
    }
}
