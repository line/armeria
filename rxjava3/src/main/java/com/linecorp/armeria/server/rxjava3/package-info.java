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

/**
 * Provides a default {@link com.linecorp.armeria.server.annotation.ResponseConverterFunction}
 * which automatically converts an {@link io.reactivex.rxjava3.core.ObservableSource} into an
 * {@link com.linecorp.armeria.common.HttpResponse} when the {@link io.reactivex.rxjava3.core.ObservableSource}
 * is returned by an annotated HTTP service.
 */
@NonNullByDefault
package com.linecorp.armeria.server.rxjava3;

import com.linecorp.armeria.common.util.NonNullByDefault;
