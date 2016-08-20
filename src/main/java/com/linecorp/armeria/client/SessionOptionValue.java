/*
 * Copyright 2015 LINE Corporation
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
package com.linecorp.armeria.client;

import com.linecorp.armeria.common.util.AbstractOptionValue;

/**
 * A value of a {@link SessionOption}.
 *
 * @param <T> the type of the option value
 */
public final class SessionOptionValue<T> extends AbstractOptionValue<SessionOption<T>, T> {

    SessionOptionValue(SessionOption<T> constant, T value) {
        super(constant, value);
    }
}
