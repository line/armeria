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

package com.linecorp.armeria.internal.common.thrift.logging;

import java.util.UUID;

import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TProtocol;

import com.linecorp.armeria.internal.common.thrift.logging.UnMaskingContexts.MetadataContext;
import com.linecorp.armeria.internal.common.thrift.logging.UnMaskingContexts.OverwriteMetadataContext;
import com.linecorp.armeria.internal.common.thrift.logging.UnMaskingContexts.PojoMetadataContext;

final class UnMaskingTProtocol extends AbstractUnMaskingTProtocol {

    UnMaskingTProtocol(TProtocol delegate, TBase<?, ?> base, TBaseSelectorCache selectorCache) {
        super(delegate, base, selectorCache);
    }

    @Override
    public UUID readUuid() throws TException {
        final MetadataContext context = stack().getFirst().resolve();
        if (context instanceof OverwriteMetadataContext) {
            final OverwriteMetadataContext overwriteMetadataContext = (OverwriteMetadataContext) context;
            return (UUID) overwriteMetadataContext.getObj();
        }
        if (!(context instanceof PojoMetadataContext)) {
            return delegate().readUuid();
        }
        final PojoMetadataContext metadataContext = (PojoMetadataContext) context;
        return tryUnmask(metadataContext, UUID.class, delegate()::readUuid, delegate());
    }

    @Override
    public void writeUuid(UUID uuid) throws TException {
        throw new UnsupportedOperationException();
    }
}
