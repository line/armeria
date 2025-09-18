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

package com.linecorp.armeria.xds;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;

import io.envoyproxy.pgv.ReflectiveValidatorIndex;
import io.envoyproxy.pgv.ValidationException;
import io.envoyproxy.pgv.Validator;
import io.envoyproxy.pgv.ValidatorIndex;

final class XdsValidatorIndex implements ValidatorIndex {

    private static final XdsValidatorIndex INSTANCE = new XdsValidatorIndex();

    static XdsValidatorIndex of() {
        return INSTANCE;
    }

    private final ValidatorIndex delegate;

    private XdsValidatorIndex() {
        delegate = new ReflectiveValidatorIndex();
    }

    @Override
    public <T> Validator<T> validatorFor(Class clazz) {
        return delegate.validatorFor(clazz);
    }

    void assertValid(Object message) {
        try {
            validatorFor(message).assertValid(message);
        } catch (ValidationException e) {
            throw new IllegalArgumentException(e);
        }
    }

    <T extends Message> T unpack(Any message, Class<T> clazz) {
        final T unpacked;
        try {
            unpacked = message.unpack(clazz);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException(e);
        }
        assertValid(unpacked);
        return unpacked;
    }
}
