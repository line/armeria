package com.linecorp.armeria.common.encoding;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBufAllocator;

/**
 * A {@link StreamDecoder} that decompresses data encoded with the zstd format ('zstd').
 */
public class ZstdStreamDecoder  extends AbstractStreamDecoder {

    ZstdStreamDecoder(ZstdStreamDecoder zstdStreamDecoder, ByteBufAllocator alloc, int maxLength) {
        super(zstdStreamDecoder, alloc, maxLength);
    }
}