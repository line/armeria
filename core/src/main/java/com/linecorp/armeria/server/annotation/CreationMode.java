/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.server.annotation;

import com.linecorp.armeria.common.DependencyInjector;

/**
 * Defines the mode of instantiation of the dependencies specified in {@link RequestConverter#value()},
 * {@link ResponseConverter#value()}, {@link ExceptionHandler#value()}, {@link Decorator#value()} and
 * {@link DecoratorFactory#value()}.
 */
public enum CreationMode {
    /**
     * Use the default constructor of the dependent class to get its instance.
     * A dependent class must have an accessible default constructor to use this mode.
     */
    REFLECTION,
    /**
     * Use {@link DependencyInjector} to get an instance of the dependent class.
     */
    INJECTION
}
