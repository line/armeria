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

package com.linecorp.armeria.xds.api;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.validator.XdsValidatorIndex;

import io.envoyproxy.pgv.ReflectiveValidatorIndex;
import io.envoyproxy.pgv.ValidationException;
import io.envoyproxy.pgv.Validator;
import io.envoyproxy.pgv.ValidatorIndex;

/**
 * The default validator which uses reflection in conjunction with pgv to validate messages.
 */
@UnstableApi
public final class DefaultXdsValidatorIndex implements ValidatorIndex, XdsValidatorIndex {

    private final ValidatorIndex delegate;

    /**
     * Creates a validator.
     */
    public DefaultXdsValidatorIndex() {
        delegate = new ReflectiveValidatorIndex();
    }

    @Override
    public <T> Validator<T> validatorFor(Class clazz) {
        return delegate.validatorFor(clazz);
    }

    @Override
    public void assertValid(Object message) {
        try {
            validatorFor(message).assertValid(message);
        } catch (ValidationException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
