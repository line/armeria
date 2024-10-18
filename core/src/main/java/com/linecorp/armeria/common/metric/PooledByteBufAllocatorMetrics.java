package com.linecorp.armeria.common.metric;

import com.linecorp.armeria.internal.common.metric.MicrometerUtil;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.internal.StringUtil;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link MeterBinder} to observe Netty {@link PooledByteBufAllocator}s. The following stats are
 * currently exported per registered {@link MeterIdPrefix}.
 *
 * <ul>
 *   <li>"pooled.byte.buf.allocator.numHeapArenas" (gauge) - the total number of Netty's heap area</li>
 *   <li>"pooled.byte.buf.allocator.numDirectArenas" (gauge)
 *     - the total number of direct arena of Netty</li>
 * </ul>
 **/
public class PooledByteBufAllocatorMetrics implements MeterBinder {

    private final PooledByteBufAllocator allocator;
    private final MeterIdPrefix idPrefix;

    /**
     * Creates an instance of {@link PooledByteBufAllocatorMetrics}.
     */
    public PooledByteBufAllocatorMetrics(PooledByteBufAllocator allocator, MeterIdPrefix idPrefix) {
        this.allocator = allocator;
        this.idPrefix = idPrefix;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        final Self metrics = MicrometerUtil.register(registry, idPrefix, Self.class, Self::new);
        metrics.add(allocator);
    }

    /**
     * An actual implementation of {@link PooledByteBufAllocatorMetrics}.
     */
    static final class Self {

        private final Set<PooledByteBufAllocator> registry = ConcurrentHashMap.newKeySet(1);

        void add(PooledByteBufAllocator allocator) {
            registry.add(allocator);
        }

        Self(MeterRegistry parent, MeterIdPrefix idPrefix) {

            final String numHeapArenas = idPrefix.name("pooled.byte.buf.allocator.numHeapArenas");
            parent.gauge(numHeapArenas, idPrefix.tags(), this,
                PooledByteBufAllocatorMetrics.Self::numHeapArenas);

            final String numDirectArenas = idPrefix.name(
                "pooled.byte.buf.allocator.numDirectArenas");
            parent.gauge(numDirectArenas, idPrefix.tags(), this,
                PooledByteBufAllocatorMetrics.Self::numDirectArenas);

            final String numThreadLocalCaches = idPrefix.name(
                "pooled.byte.buf.allocator.numThreadLocalCaches");
            parent.gauge(numThreadLocalCaches, idPrefix.tags(), this,
                PooledByteBufAllocatorMetrics.Self::numThreadLocalCaches);

            final String pendingTasks = idPrefix.name("pooled.byte.buf.allocator.tinyCacheSize");
            parent.gauge(pendingTasks, idPrefix.tags(), this,
                PooledByteBufAllocatorMetrics.Self::tinyCacheSize);

            final String tinyCacheSize = idPrefix.name("pooled.byte.buf.allocator.smallCacheSize");
            parent.gauge(tinyCacheSize, idPrefix.tags(), this,
                PooledByteBufAllocatorMetrics.Self::smallCacheSize);

            final String normalCacheSize = idPrefix.name(
                "pooled.byte.buf.allocator.normalCacheSize");
            parent.gauge(normalCacheSize, idPrefix.tags(), this,
                PooledByteBufAllocatorMetrics.Self::normalCacheSize);

            final String chunkSize = idPrefix.name("pooled.byte.buf.allocator.chunkSize");
            parent.gauge(chunkSize, idPrefix.tags(), this,
                PooledByteBufAllocatorMetrics.Self::chunkSize);
        }

        /**
         * Return the number of heap arenas.
         */
        public int numHeapArenas() {
            Optional<PooledByteBufAllocator> first = registry.stream().findFirst();
            PooledByteBufAllocator allocator = first.get();
            return allocator.numHeapArenas();
        }

        /**
         * Return the number of direct arenas.
         */
        public int numDirectArenas() {
            Optional<PooledByteBufAllocator> first = registry.stream().findFirst();
            PooledByteBufAllocator allocator = first.get();
            return allocator.numDirectArenas();
        }

        /**
         * Return the number of thread local caches used by this {@link PooledByteBufAllocator}.
         */
        public int numThreadLocalCaches() {
            Optional<PooledByteBufAllocator> first = registry.stream().findFirst();
            PooledByteBufAllocator allocator = first.get();
            return allocator.numThreadLocalCaches();
        }

        /**
         * Return the size of the tiny cache.
         *
         * @deprecated Tiny caches have been merged into small caches.
         */
        @Deprecated
        public int tinyCacheSize() {
            Optional<PooledByteBufAllocator> first = registry.stream().findFirst();
            PooledByteBufAllocator allocator = first.get();
            return allocator.tinyCacheSize();
        }

        /**
         * Return the size of the small cache.
         */
        public int smallCacheSize() {
            Optional<PooledByteBufAllocator> first = registry.stream().findFirst();
            PooledByteBufAllocator allocator = first.get();
            return allocator.smallCacheSize();
        }

        /**
         * Return the size of the normal cache.
         */
        public int normalCacheSize() {
            Optional<PooledByteBufAllocator> first = registry.stream().findFirst();
            PooledByteBufAllocator allocator = first.get();
            return allocator.normalCacheSize();
        }

        /**
         * Return the chunk size for an arena.
         */
        public int chunkSize() {
            Optional<PooledByteBufAllocator> first = registry.stream().findFirst();
            PooledByteBufAllocator allocator = first.get();
            return allocator.chunkSize();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(256);
            sb.append(StringUtil.simpleClassName(this))
                .append("; numHeapArenas: ").append(numHeapArenas())
                .append("; numDirectArenas: ").append(numDirectArenas())
                .append("; smallCacheSize: ").append(smallCacheSize())
                .append("; normalCacheSize: ").append(normalCacheSize())
                .append("; numThreadLocalCaches: ").append(numThreadLocalCaches())
                .append("; chunkSize: ").append(chunkSize()).append(')');
            return sb.toString();
        }
    }
}
