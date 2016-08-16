/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.common;

import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.thrift.ThriftCall;

/**
 * A request. It is usually one of the following:
 * <ul>
 *   <li>A {@link StreamMessage} with some initial information (if necessary)
 *     <ul>
 *       <li>e.g. {@link HttpRequest} whose initial information is its initial HTTP headers</li>
 *     </ul>
 *   </li>
 *   <li>A simple object whose content is readily available. e.g. {@link ThriftCall}</li>
 * </ul>
 */
public interface Request {}
