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
// =================================================================================================
// Copyright 2011 Twitter, Inc.
// -------------------------------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this work except in compliance with the License.
// You may obtain a copy of the License in the LICENSE file, or at:
//
//  https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// =================================================================================================

/**
 * A Thrift JSON protocol that supports field names as defined in the IDL.
 * This can be used for creating debug endpoints.
 * <p>
 * This was forked from <a href=https://github.com/twitter/commons>Twitter Commons</a> with the following
 * modifications:
 * <ul>
 *     <li>Change package name</li>
 *     <li>Replace Gson with Jackson</li>
 *     <li>Add support for encoding RPCs</li>
 *     <li>Remove obsolete TODOs</li>
 *     <li>Reformat code to armeria specification</li>
 *     <li>Miscellaneous style cleanups</li>
 * </ul>
 */
package com.linecorp.armeria.common.thrift.text;
