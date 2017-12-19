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
package com.linecorp.armeria.common.util;

import io.netty.util.AbstractConstant;

/**
 * A configuration option.
 *
 * @param <T> the type of the value of the option
 *
 * @see AbstractOptionValue
 * @see AbstractOptions
 */
@SuppressWarnings({ "rawtypes", "UnusedDeclaration" })
public abstract class AbstractOption<T> extends AbstractConstant {

    /**
     * Creates a new instance.
     *
     * @param id the integral ID of this option
     * @param name the name of this option
     */
    protected AbstractOption(int id, String name) {
        super(id, name);
    }
}
