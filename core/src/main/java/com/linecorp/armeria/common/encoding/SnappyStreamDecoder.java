package com.linecorp.armeria.common.encoding;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.compression.SnappyFrameDecoder;

/**
 * A {@link StreamDecoder} that decompresses data encoded with the snappy format ('snappy').
 */
public class SnappyStreamDecoder  extends AbstractStreamDecoder {

    SnappyStreamDecoder(SnappyFrameDecoder snappyFrameDecoder, ByteBufAllocator alloc, int maxLength) {
        super(snappyFrameDecoder, alloc, maxLength);
    }
}