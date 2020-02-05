/*
 * Copyright 2018 LINE Corporation
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
 * Utilities for working with {@link io.netty.buffer.ByteBuf} in an unsafe way. These can improve performance
 * when dealing with large buffers but require careful memory management or there will be memory leaks. Only use
 * these methods if you really know what you're doing.
 */
@UnstableApi
@NonNullByDefault
package com.linecorp.armeria.unsafe;

import com.linecorp.armeria.common.util.NonNullByDefault;
import com.linecorp.armeria.common.util.UnstableApi;
