/*
 *  Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.internal.common.grpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.ExchangeType;

import io.grpc.MethodDescriptor.MethodType;

public final class GrpcExchangeTypeUtil {

    private static final Logger logger = LoggerFactory.getLogger(GrpcExchangeTypeUtil.class);

    private static boolean warnedUnknownMethodType;

    public static ExchangeType toExchangeType(MethodType methodType) {
        switch (methodType) {
            case UNARY:
                return ExchangeType.UNARY;
            case CLIENT_STREAMING:
                return ExchangeType.REQUEST_STREAMING;
            case SERVER_STREAMING:
                return ExchangeType.RESPONSE_STREAMING;
            case BIDI_STREAMING:
                return ExchangeType.BIDI_STREAMING;
            default:
                if (!warnedUnknownMethodType) {
                    warnedUnknownMethodType = true;
                    logger.warn("Unknown MethodType: {}; using {}", methodType, ExchangeType.BIDI_STREAMING);
                }
                return ExchangeType.BIDI_STREAMING;
        }
    }

    private GrpcExchangeTypeUtil() {}
}
