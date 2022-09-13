package rocks.inspectit.ocelot.core.metrics.percentiles;

import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.internal.export.MetricProducer;

import java.time.Duration;
import java.util.Collection;
import java.util.function.Supplier;

/**
 * A metric producer which caches the metrics for a specified amount of time.
 */
public class CachingMetricProducer implements MetricProducer {

    /**
     * The function invoked to generate the metrics.
     */
    private final Supplier<Collection<MetricData>> computeMetricsFunction;

    /**
     * The duration for which cached metrics are kept.
     */
    private final long cacheDurationNanos;

    /**
     * The timestamp when the metrics were computed the last time.
     */
    private long cacheTimestamp;

    private Collection<MetricData> cachedMetrics = null;

    /**
     * Constructor.
     *
     * @param computeMetricsFunction the function to invoke for computing the metrics
     * @param cacheDuration          the duration for which the values shall be cached.
     */
    public CachingMetricProducer(Supplier<Collection<MetricData>> computeMetricsFunction, Duration cacheDuration) {
        this.computeMetricsFunction = computeMetricsFunction;
        cacheDurationNanos = cacheDuration.toNanos();
    }

    @Override
    public synchronized Collection<MetricData> collectAllMetrics() {
        long now = System.nanoTime();
        if (cachedMetrics == null || (now - cacheTimestamp) > cacheDurationNanos) {
            cachedMetrics = computeMetricsFunction.get();
            cacheTimestamp = now;
        }
        return cachedMetrics;
    }
}
