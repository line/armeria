/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.common.thrift.text;

import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.transport.TTransport;

/**
 * The {@link TProtocolFactory} that creates a {@link TTextProtocol}.
 */
public final class TTextProtocolFactory implements TProtocolFactory {

    private static final long serialVersionUID = -7323272088544581160L;

    private static final TTextProtocolFactory INSTANCE = new TTextProtocolFactory(false);
    private static final TTextProtocolFactory INSTANCE_NAMED_ENUMS = new TTextProtocolFactory(true);

    private final boolean useNamedEnums;

    /**
     * Returns the singleton {@link TTextProtocolFactory} instance.
     */
    public static TTextProtocolFactory get() {
        return get(false);
    }

    /**
     * Returns the singleton {@link TTextProtocolFactory} instance,
     * with optional serialization of named enums.
     */
    public static TTextProtocolFactory get(boolean useNamedEnums) {
        return useNamedEnums ? INSTANCE_NAMED_ENUMS : INSTANCE;
    }

    private TTextProtocolFactory(boolean useNamedEnums) {
        this.useNamedEnums = useNamedEnums;
    }

    /**
     * Returns whether the serialization of named enums is enabled.
     */
    public boolean usesNamedEnums() {
        return useNamedEnums;
    }

    @Override
    public TProtocol getProtocol(TTransport trans) {
        return new TTextProtocol(trans, useNamedEnums);
    }

    @Override
    public String toString() {
        return useNamedEnums ? "TProtocolFactory(TTEXT_NAMED_ENUM)" : "TProtocolFactory(TTEXT)";
    }
}
