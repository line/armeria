/*
 * Copyright 2019 LINE Corporation
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

/**
 * Distributed tracing services based on <a href="https://github.com/openzipkin/brave">Brave</a>,
 * a Java tracing library compatible with <a href="http://zipkin.io/">Zipkin</a>.
 *
 * @deprecated Use armeria-brave6 module.
 *             See <a href="https://armeria.dev/release-notes/1.28.0#%EF%B8%8F-breaking-changes">
 *             1.28 Breaking Changes</a> for more information.
 */
@Deprecated
@NonNullByDefault
package com.linecorp.armeria.internal.common.brave;

import com.linecorp.armeria.common.annotation.NonNullByDefault;
