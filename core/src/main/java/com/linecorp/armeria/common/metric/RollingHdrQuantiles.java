/*
 * Copyright 2017 LINE Corporation
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
/*
 *
 *  Copyright 2016 Vladimir Bukhtoyarov
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.linecorp.armeria.common.metric;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import org.HdrHistogram.DoubleHistogram;
import org.HdrHistogram.DoubleRecorder;

import com.google.common.annotations.VisibleForTesting;

import io.micrometer.core.instrument.stats.quantile.Quantiles;

final class RollingHdrQuantiles implements Quantiles {

    private static final double[] PREDEFINED_QUANTILES = { 0, 0.5, 0.75, 0.9, 0.95, 0.98, 0.99, 0.999, 1.0 };
    private static final Collection<Double> MONITORED = Arrays.stream(PREDEFINED_QUANTILES).boxed()
                                                              .collect(toImmutableList());

    @VisibleForTesting
    static final int NUM_SIGNIFICANT_VALUE_DIGITS = 2;

    /**
     * A value less than this value will be replaced so that it does not exceed the dynamic range limits of
     * {@link DoubleRecorder}.
     */
    @VisibleForTesting
    static final double MIN_VALUE = 1.0 / TimeUnit.SECONDS.toMillis(1);

    /**
     * A value greater than this value will be replaced so that it does not exceed the dynamic range limits of
     * {@link DoubleRecorder}.
     */
    @VisibleForTesting
    static final double MAX_VALUE = 70368744177663L;

    private final long intervalBetweenResettingMillis;
    private final long creationTimestamp;
    private final ArchivedHistogram[] archive;
    private final boolean historySupported;
    private final LongSupplier clock;
    private final DoubleHistogram temporarySnapshotHistogram;

    private final Phase left;
    private final Phase right;
    private final Phase[] phases;
    private final AtomicReference<Phase> currentPhaseRef;

    private double[] snapshot;
    private long lastSnapshotTimeMillis;

    RollingHdrQuantiles() {
        this(5, Duration.ofMinutes(2).toMillis(), System::currentTimeMillis);
    }

    RollingHdrQuantiles(int numberHistoryChunks, long intervalBetweenResettingMillis) {
        this(numberHistoryChunks, intervalBetweenResettingMillis, System::currentTimeMillis);
    }

    RollingHdrQuantiles(int numberHistoryChunks, long intervalBetweenResettingMillis,
                        LongSupplier clock) {

        this.intervalBetweenResettingMillis = intervalBetweenResettingMillis;
        this.clock = clock;
        creationTimestamp = clock.getAsLong();

        left = new Phase(creationTimestamp + intervalBetweenResettingMillis);
        right = new Phase(Long.MAX_VALUE);
        phases = new Phase[] { left, right };
        currentPhaseRef = new AtomicReference<>(left);

        historySupported = numberHistoryChunks > 0;
        if (historySupported) {
            archive = new ArchivedHistogram[numberHistoryChunks];
            for (int i = 0; i < numberHistoryChunks; i++) {
                final DoubleHistogram archivedHistogram = createNonConcurrentCopy(left.intervalHistogram);
                archive[i] = new ArchivedHistogram(archivedHistogram, Long.MIN_VALUE);
            }
        } else {
            archive = null;
        }

        temporarySnapshotHistogram = createNonConcurrentCopy(left.intervalHistogram);
    }

    @Override
    public void observe(double value) {
        observe(value, 0);
    }

    public void observe(double value, double expectedIntervalBetweenValueSamples) {
        value = Math.max(Math.min(value, MAX_VALUE), MIN_VALUE);

        final long currentTimeMillis = clock.getAsLong();
        final Phase currentPhase = currentPhaseRef.get();
        if (currentTimeMillis < currentPhase.proposedInvalidationTimestamp) {
            safeRecord(currentPhase, value, expectedIntervalBetweenValueSamples);
            return;
        }

        final Phase nextPhase = currentPhase == left ? right : left;
        safeRecord(nextPhase, value, expectedIntervalBetweenValueSamples);

        if (!currentPhaseRef.compareAndSet(currentPhase, nextPhase)) {
            // another writer achieved progress and must submit rotation task to backgroundExecutor
            return;
        }

        // Current thread is responsible to rotate phases.
        rotate(currentTimeMillis, currentPhase, nextPhase);
    }

    private void safeRecord(Phase phase, double value, double expectedIntervalBetweenValueSamples) {
        try {
            phase.recorder.recordValueWithExpectedInterval(value, expectedIntervalBetweenValueSamples);
        } catch (IndexOutOfBoundsException ignored) {
            // Out of dynamic range?
        }
    }

    private synchronized void rotate(long currentTimeMillis, Phase currentPhase, Phase nextPhase) {
        try {
            currentPhase.intervalHistogram =
                    currentPhase.recorder.getIntervalHistogram(currentPhase.intervalHistogram);
            addSecondToFirst(currentPhase.totalsHistogram, currentPhase.intervalHistogram);
            if (historySupported) {
                // move values from recorder to correspondent archived histogram
                final long currentPhaseNumber =
                        (currentPhase.proposedInvalidationTimestamp - creationTimestamp) /
                        intervalBetweenResettingMillis;
                final int correspondentArchiveIndex = (int) ((currentPhaseNumber - 1) % archive.length);
                final ArchivedHistogram correspondentArchivedHistogram = archive[correspondentArchiveIndex];
                reset(correspondentArchivedHistogram.histogram);
                addSecondToFirst(correspondentArchivedHistogram.histogram, currentPhase.totalsHistogram);
                correspondentArchivedHistogram.proposedInvalidationTimestamp =
                        currentPhase.proposedInvalidationTimestamp +
                        archive.length * intervalBetweenResettingMillis;
            }
            reset(currentPhase.totalsHistogram);
        } finally {
            final long millisSinceCreation = currentTimeMillis - creationTimestamp;
            final long intervalsSinceCreation = millisSinceCreation / intervalBetweenResettingMillis;
            currentPhase.proposedInvalidationTimestamp = Long.MAX_VALUE;
            nextPhase.proposedInvalidationTimestamp =
                    creationTimestamp + (intervalsSinceCreation + 1) * intervalBetweenResettingMillis;
        }
    }

    @Override
    public Double get(double percentile) {
        final double[] snapshot = getSnapshot();
        for (int i = 0; i < PREDEFINED_QUANTILES.length; i++) {
            if (percentile <= PREDEFINED_QUANTILES[i]) {
                return snapshot[i];
            }
        }
        return snapshot[PREDEFINED_QUANTILES.length - 1];
    }

    @Override
    public Collection<Double> monitored() {
        return MONITORED;
    }

    private synchronized double[] getSnapshot() {
        final long currentTimeMillis = clock.getAsLong();
        final double[] oldSnapshot = snapshot;
        if (oldSnapshot != null && currentTimeMillis - lastSnapshotTimeMillis < 1000) {
            return oldSnapshot;
        }

        reset(temporarySnapshotHistogram);

        for (Phase phase : phases) {
            if (phase.isNeedToBeReportedToSnapshot(currentTimeMillis)) {
                phase.intervalHistogram = phase.recorder.getIntervalHistogram(phase.intervalHistogram);
                addSecondToFirst(phase.totalsHistogram, phase.intervalHistogram);
                addSecondToFirst(temporarySnapshotHistogram, phase.totalsHistogram);
            }
        }
        if (historySupported) {
            for (ArchivedHistogram archivedHistogram : archive) {
                if (archivedHistogram.proposedInvalidationTimestamp > currentTimeMillis) {
                    addSecondToFirst(temporarySnapshotHistogram, archivedHistogram.histogram);
                }
            }
        }

        final double[] snapshot = new double[PREDEFINED_QUANTILES.length];
        for (int i = 0; i < PREDEFINED_QUANTILES.length; i++) {
            final double percentile = PREDEFINED_QUANTILES[i] * 100.0;
            snapshot[i] = temporarySnapshotHistogram.getValueAtPercentile(percentile);
        }
        this.snapshot = snapshot;
        lastSnapshotTimeMillis = currentTimeMillis;
        return snapshot;
    }

    @VisibleForTesting
    synchronized void discardCachedSnapshot() {
        snapshot = null;
    }

    @VisibleForTesting
    int estimatedFootprintInBytes() {
        // each histogram has equivalent pessimistic estimation
        int oneHistogramPessimisticFootprint = temporarySnapshotHistogram.getEstimatedFootprintInBytes();

        // 4 - two recorders with two histogram
        // 2 - two histogram for storing accumulated values from current phase
        // 1 - temporary histogram used for snapshot extracting
        return oneHistogramPessimisticFootprint * ((archive != null ? archive.length : 0) + 4 + 2 + 1);
    }

    private static void reset(DoubleHistogram histogram) {
        if (histogram.getTotalCount() > 0) {
            histogram.reset();
        }
    }

    private static void addSecondToFirst(DoubleHistogram first, DoubleHistogram second) {
        if (second.getTotalCount() > 0) {
            first.add(second);
        }
    }

    private static DoubleHistogram createNonConcurrentCopy(DoubleHistogram source) {
        final DoubleHistogram copy = new DoubleHistogram(
                source.getHighestToLowestValueRatio(),
                source.getNumberOfSignificantValueDigits());
        copy.setAutoResize(source.isAutoResize());
        return copy;
    }

    private static String histogramToString(DoubleHistogram histogram) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            final PrintStream writer = new PrintStream(baos);
            histogram.outputPercentileDistribution(writer, 1.0);
            return new String(baos.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String printArray(Object[] array, String elementName) {
        final StringBuilder buf = new StringBuilder();
        buf.append('{');
        for (int i = 0; i < array.length; i++) {
            buf.append('\n')
               .append(elementName)
               .append('[').append(i).append("]=")
               .append(array[i]);
        }
        buf.append("\n}");
        return buf.toString();
    }

    private static final class ArchivedHistogram {

        final DoubleHistogram histogram;
        volatile long proposedInvalidationTimestamp;

        ArchivedHistogram(DoubleHistogram histogram, long proposedInvalidationTimestamp) {
            this.histogram = histogram;
            this.proposedInvalidationTimestamp = proposedInvalidationTimestamp;
        }

        @Override
        public String toString() {
            return "ArchivedHistogram{" +
                    "\n, proposedInvalidationTimestamp=" + proposedInvalidationTimestamp +
                    "\n, histogram=" + histogramToString(histogram) +
                    "\n}";
        }
    }

    private final class Phase {

        final DoubleRecorder recorder;
        final DoubleHistogram totalsHistogram;
        DoubleHistogram intervalHistogram;
        volatile long proposedInvalidationTimestamp;

        Phase(long proposedInvalidationTimestamp) {
            recorder = new DoubleRecorder(NUM_SIGNIFICANT_VALUE_DIGITS);
            intervalHistogram = recorder.getIntervalHistogram();
            intervalHistogram.setAutoResize(true);
            totalsHistogram = intervalHistogram.copy();
            totalsHistogram.setAutoResize(true);
            this.proposedInvalidationTimestamp = proposedInvalidationTimestamp;
        }

        @Override
        public String toString() {
            return "Phase{" +
                    "\n, proposedInvalidationTimestamp=" + proposedInvalidationTimestamp +
                    "\n, totalsHistogram=" + (totalsHistogram != null ? histogramToString(totalsHistogram)
                                                                      : "null") +
                    "\n, intervalHistogram=" + histogramToString(intervalHistogram) +
                    "\n}";
        }

        boolean isNeedToBeReportedToSnapshot(long currentTimeMillis) {
            final long proposedInvalidationTimestampLocal = proposedInvalidationTimestamp;
            if (proposedInvalidationTimestampLocal > currentTimeMillis) {
                return true;
            }
            if (!historySupported) {
                return false;
            }
            final long correspondentChunkProposedInvalidationTimestamp =
                    proposedInvalidationTimestampLocal + archive.length * intervalBetweenResettingMillis;
            return correspondentChunkProposedInvalidationTimestamp > currentTimeMillis;
        }
    }

    @Override
    public String toString() {
        return "RollingHdrQuantiles{" +
                "\nintervalBetweenResettingMillis=" + intervalBetweenResettingMillis +
                ",\n creationTimestamp=" + creationTimestamp +
                (!historySupported ? "" : ",\n archive=" + printArray(archive, "chunk")) +
                ",\n left=" + left +
                ",\n right=" + right +
                ",\n currentPhase=" + (currentPhaseRef.get() == left ? "left" : "right") +
                ",\n temporarySnapshotHistogram=" + histogramToString(temporarySnapshotHistogram)  +
                '}';
    }
}
