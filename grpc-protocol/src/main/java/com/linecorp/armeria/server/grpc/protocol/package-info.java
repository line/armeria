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
 * {@link com.linecorp.armeria.server.HttpService} implementations for handling the gRPC wire protocol
 * without support for gRPC generated code stubs. This package is separated for advanced users that would
 * like to use the gRPC wire protocol without depending on gRPC itself. This package must not depend on
 * any dependencies outside of {@code armeria-core}.
 *
 * <p>Don't use this package unless you know what you're doing, it is generally recommended to use
 * {@code com.linecorp.armeria.server.grpc.GrpcService} provided by the {@code armeria-grpc} module.</p>
 *
 * <p>The classes in this package, unlike other packages, are not guaranteed to be backward compatible since
 * it's an advanced API.</p>
 */
@UnstableApi
@NonNullByDefault
package com.linecorp.armeria.server.grpc.protocol;

import com.linecorp.armeria.common.util.NonNullByDefault;
import com.linecorp.armeria.common.util.UnstableApi;
