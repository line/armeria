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

package com.linecorp.armeria.common.thrift.logging;

import org.apache.thrift.meta_data.FieldMetaData;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.FieldMasker;

/**
 * Holds information about a thrift struct field.
 * Users may use the information conveyed in this object to decide whether to mask a field
 * via {@link FieldMasker}.
 */
@UnstableApi
public interface ThriftFieldInfo {

    /**
     * The {@link FieldMetaData} for a field that can be obtained via
     * {@link FieldMetaData#getStructMetaDataMap(Class)}.
     */
    FieldMetaData fieldMetaData();
}
