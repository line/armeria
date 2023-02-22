package com.linecorp.armeria.server.healthcheck;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.List;

public class DefaultMemoryHealthChecker implements HealthChecker {

    private final List<MemoryPoolMXBean> memoryPoolMXBeans;
    private final double targetMemoryHeapUsage;
    private final double targetMemoryNonHeapUsage;
    private final double targetMemoryTotalUsage;

    public DefaultMemoryHealthChecker(final double targetMemoryHeapUsage, final double targetMemoryNonHeapUsage, final double targetMemoryTotalUsage) {
        this.targetMemoryHeapUsage = targetMemoryHeapUsage;
        this.targetMemoryNonHeapUsage = targetMemoryNonHeapUsage;
        this.targetMemoryTotalUsage = targetMemoryTotalUsage;
        this.memoryPoolMXBeans = ManagementFactory.getMemoryPoolMXBeans();
    }

    @Override
    public boolean isHealthy() {
        final double heapMemoryUsage = getHeapMemoryUsage();
        final double nonHeapMemoryUsage = getNonHeapMemoryUsage();
        final double totalMemoryUsage = getTotalMemoryUsage();
        return heapMemoryUsage <= targetMemoryHeapUsage &&
               nonHeapMemoryUsage <= targetMemoryNonHeapUsage &&
               totalMemoryUsage <= targetMemoryTotalUsage;
    }

    private double getHeapMemoryUsage() {
        return this.memoryPoolMXBeans.stream().filter(e -> MemoryType.HEAP.equals(e.getType())).map(e -> e.getUsage().getUsed()).mapToDouble(e -> e).sum();
    }

    private double getNonHeapMemoryUsage() {
        return ManagementFactory.getPlatformMXBean(BufferPoolMXBean.class).getMemoryUsed();
    }

    private double getTotalMemoryUsage() {
        return Runtime.getRuntime().totalMemory();
    }
}
