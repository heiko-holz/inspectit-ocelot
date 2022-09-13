package rocks.inspectit.ocelot.core.metrics.percentiles;

import com.google.common.annotations.VisibleForTesting;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.InstrumentValueType;
import io.opentelemetry.sdk.metrics.data.GaugeData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.PointData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableDoublePointData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableGaugeData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableMetricData;
import io.opentelemetry.sdk.metrics.internal.descriptor.InstrumentDescriptor;
import io.opentelemetry.sdk.resources.Resource;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.stat.descriptive.rank.Percentile;
import org.springframework.beans.factory.annotation.Autowired;
import rocks.inspectit.ocelot.core.opentelemetry.OpenTelemetryControllerImpl;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Holds the data for a given measurement splitted by a provided set of tags over a given time window.
 * For the data within this window, percentiles and min / max values can be computed.
 */
@Slf4j
public class PercentileView {

    private static final Duration CLEANUP_INTERVAL = Duration.ofSeconds(1);

    /**
     * The tag to use for the percentile or "min","max" respectively.
     */
    private static final String PERCENTILE_TAG_KEY = "quantile";

    /**
     * The tag value to use for {@link #PERCENTILE_TAG_KEY} for the "minimum" series.
     */
    private static final String MIN_METRIC_SUFFIX = "_min";

    /**
     * The tag value to use for {@link #PERCENTILE_TAG_KEY} for the "maximum" series.
     */
    private static final String MAX_METRIC_SUFFIX = "_max";

    /**
     * The formatter used to print percentiles to tags.
     */
    private static final DecimalFormat PERCENTILE_TAG_FORMATTER = new DecimalFormat("#.#####", DecimalFormatSymbols.getInstance(Locale.ENGLISH));

    /**
     * The descriptor of the metric for this view.
     */
    private InstrumentDescriptor percentileMetricDescriptor;

    /**
     * If not null, the minimum value will be exposed as this gauge.
     */
    private InstrumentDescriptor minMetricDescriptor;

    /**
     * If not null, the maximum value will be exposed as this gauge.
     */
    private InstrumentDescriptor maxMetricDescriptor;

    /**
     * The percentiles to compute in the range (0,1)
     */
    @Getter
    private Set<Double> percentiles;

    /**
     * Defines the tags which are used for the view.
     * E.g. if the tag "http_path" is used, percentiles will be computed for each http_path individually.
     * <p>
     * The tag values are stored in a fixed order in the keys of {@link #seriesValues} for each series.
     * The values within {@link #tagIndices} define at which position within these arrays the corresponding tag value is found.
     * E.g. if tagIndex["http_path"] = 2, this means that the values for http_path will be at index 2 in the keys of {@link #seriesValues}.
     */
    private Map<String, Integer> tagIndices;

    /**
     * Stores the buffered data of the sliding time window for each time series.
     */
    private ConcurrentHashMap<List<String>, WindowedDoubleQueue> seriesValues;

    /**
     * Defines the size of the sliding window in milliseconds.
     * E.g. a value of 15000 means that percentiles will be computed based on the last 15 seconds.
     */
    @Getter
    private long timeWindowMillis;

    /**
     * The name of the view, used as prefix for all individual metrics.
     */
    @Getter
    private String viewName;

    /**
     * The unit of the measure.
     */
    @Getter
    private String unit;

    /**
     * The description of this view.
     */
    @Getter
    private String description;

    /**
     * The maximum amount of measurement points to buffer.
     * If this limit is reached, new measuremetns will be rejected until there is space again.
     */
    @Getter
    private int bufferLimit;

    private boolean overflowWarningPrinted = false;

    /**
     * The current number of points stored in this view, limited by {@link #bufferLimit}.
     */
    private AtomicInteger numberOfPoints;

    /**
     * The timestamp when the last full cleanup happened.
     */
    private AtomicLong lastCleanupTimeMs;

    @Autowired
    OpenTelemetryControllerImpl openTelemetryController;

    /**
     * Constructor.
     *
     * @param includeMin       true, if the minimum value should be exposed as metric
     * @param includeMax       true, if the maximum value should be exposed as metric
     * @param percentiles      the set of percentiles in the range (0,1) which shall be provided as metrics
     * @param tags             the tags to use for this view
     * @param timeWindowMillis the time range in milliseconds to use for computing minimum / maximum and percentile values
     * @param viewName         the prefix to use for the names of all exposed metrics
     * @param unit             the unit of the measure
     * @param description      the description of this view
     * @param bufferLimit      the maximum number of measurements to be buffered by this view
     */
    PercentileView(boolean includeMin, boolean includeMax, Set<Double> percentiles, Set<String> tags, long timeWindowMillis, String viewName, String unit, String description, int bufferLimit) {
        validateConfiguration(includeMin, includeMax, percentiles, timeWindowMillis, viewName, unit, description, bufferLimit);
        assignTagIndices(tags);
        seriesValues = new ConcurrentHashMap<>();
        this.timeWindowMillis = timeWindowMillis;
        this.viewName = viewName;
        this.unit = unit;
        this.description = description;
        this.percentiles = new HashSet<>(percentiles);
        this.bufferLimit = bufferLimit;
        numberOfPoints = new AtomicInteger(0);
        lastCleanupTimeMs = new AtomicLong(0);
        if (!percentiles.isEmpty()) {
            percentileMetricDescriptor = InstrumentDescriptor.create(viewName, description, unit, InstrumentType.OBSERVABLE_GAUGE, InstrumentValueType.DOUBLE);
        }
        if (includeMin) {
            minMetricDescriptor = InstrumentDescriptor.create(viewName + MIN_METRIC_SUFFIX, description, unit, InstrumentType.OBSERVABLE_GAUGE, InstrumentValueType.DOUBLE);
        }
        if (includeMax) {
            maxMetricDescriptor = InstrumentDescriptor.create(viewName + MAX_METRIC_SUFFIX, description, unit, InstrumentType.OBSERVABLE_GAUGE, InstrumentValueType.DOUBLE);
        }
    }

    private void validateConfiguration(boolean includeMin, boolean includeMax, Set<Double> percentiles, long timeWindowMillis, String baseViewName, String unit, String description, int bufferLimit) {
        percentiles.stream().filter(p -> p <= 0.0 || p >= 1.0).forEach(p -> {
            throw new IllegalArgumentException("Percentiles must be in range (0,1)");
        });
        if (StringUtils.isBlank(baseViewName)) {
            throw new IllegalArgumentException("View name must not be blank!");
        }
        if (StringUtils.isBlank(description)) {
            throw new IllegalArgumentException("Description must not be blank!");
        }
        if (StringUtils.isBlank(unit)) {
            throw new IllegalArgumentException("Unit must not be blank!");
        }
        if (timeWindowMillis <= 0) {
            throw new IllegalArgumentException("Time window must not be positive!");
        }
        if (percentiles.isEmpty() && !includeMin && !includeMax) {
            throw new IllegalArgumentException("You must specify at least one percentile or enable minimum or maximum computation!");
        }
        if (bufferLimit < 1) {
            throw new IllegalArgumentException("The buffer limit must be greater than or equal to 1!");
        }
    }

    private void assignTagIndices(Set<String> tags) {
        tagIndices = new HashMap<>();
        int idx = 0;
        for (String tag : tags) {
            tagIndices.put(tag, idx);
            idx++;
        }
    }

    /**
     * Adds the provided value to the sliding window of data.
     *
     * @param value      the value of the measure
     * @param time       the timestamp when this value was observed
     * @param tagContext the tags with which this value was observed
     *
     * @return true, if the point could be added, false otherwise.
     */
    boolean insertValue(double value, long time, Baggage tagContext) {
        removeStalePointsIfTimeThresholdExceeded(time);
        List<String> tags = getTagsList(tagContext);
        WindowedDoubleQueue queue = seriesValues.computeIfAbsent(tags, (key) -> new WindowedDoubleQueue(timeWindowMillis));
        synchronized (queue) {
            long timeMillis = getInMillis(time);
            int removed = queue.removeStaleValues(timeMillis);
            int currentSize = numberOfPoints.addAndGet(-removed);
            if (currentSize < bufferLimit) {
                numberOfPoints.incrementAndGet();
                queue.insert(value, timeMillis);
            } else {
                if (!overflowWarningPrinted) {
                    overflowWarningPrinted = true;
                    log.warn("Dropping points for Percentiles-View '{}' because the buffer limit has been reached!" + " Quantiles/Min/Max will be meaningless." + " This warning will not be shown for future drops!", viewName);
                }
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the name of the series exposed by this view.
     * This can be up to three series, depending on whether min/max and quantiles are enabled.
     *
     * @return the names of the exposed series.
     */
    Set<String> getSeriesNames() {
        Set<String> result = new HashSet<>();
        if (minMetricDescriptor != null) {
            result.add(minMetricDescriptor.getName());
        }
        if (maxMetricDescriptor != null) {
            result.add(maxMetricDescriptor.getName());
        }
        if (!percentiles.isEmpty()) {
            result.add(percentileMetricDescriptor.getName());
        }
        return result;
    }

    /**
     * Removes all data which has fallen out of the time window based on the given timestamp.
     *
     * @param time the current time
     */
    private void removeStalePoints(long time) {
        long timeMillis = getInMillis(time);
        lastCleanupTimeMs.set(timeMillis);
        for (WindowedDoubleQueue queue : seriesValues.values()) {
            synchronized (queue) {
                int removed = queue.removeStaleValues(timeMillis);
                numberOfPoints.getAndAdd(-removed);
            }
        }
    }

    /**
     * Removes all data which has fallen out of the time window based on the given timestamp.
     * Only performs the cleanup if the last cleanup has been done more than {@link #CLEANUP_INTERVAL} ago
     * and the buffer is running on it's capacity limit.
     *
     * @param time the current time
     */
    private void removeStalePointsIfTimeThresholdExceeded(long time) {
        long timeMillis = getInMillis(time);
        long lastCleanupTime = lastCleanupTimeMs.get();
        boolean timeThresholdExceeded = timeMillis - lastCleanupTime > CLEANUP_INTERVAL.toMillis();
        if (timeThresholdExceeded && numberOfPoints.get() >= bufferLimit) {
            removeStalePoints(time);
        }
    }

    /**
     * @return the tags used for this view
     */
    Set<String> getTagKeys() {
        return tagIndices.keySet();
    }

    /**
     * Computes the defined percentile and min / max metrics.
     *
     * @param time the current timestamp
     *
     * @return the metrics containing the percentiles and min / max
     */
    Collection<MetricData> computeMetrics(long time) {
        removeStalePoints(time);
        ResultSeriesCollector resultSeries = new ResultSeriesCollector();
        for (Map.Entry<List<String>, WindowedDoubleQueue> series : seriesValues.entrySet()) {
            List<String> tagValues = series.getKey();
            WindowedDoubleQueue queue = series.getValue();
            double[] data = null;
            synchronized (queue) {
                int size = queue.size();
                if (size > 0) {
                    data = queue.copy();
                }
            }
            if (data != null) {
                computeSeries(tagValues, data, time, resultSeries);
            }
        }

        // TODO: get current resource (via OpenTelemetryController?)
        Resource resource = Resource.getDefault();
        List<MetricData> resultMetrics = new ArrayList<>();
        if (!percentiles.isEmpty()) {
            ImmutableMetricData.createDoubleGauge(resource, InstrumentationScopeInfo.create(percentileMetricDescriptor.getName()), percentileMetricDescriptor.getName(), percentileMetricDescriptor.getDescription(), percentileMetricDescriptor.getUnit(), resultSeries.percentileSeries);

        }
        if (isMinEnabled()) {
            ImmutableMetricData.createDoubleGauge(resource, InstrumentationScopeInfo.create(minMetricDescriptor.getName()), minMetricDescriptor.getName(), minMetricDescriptor.getDescription(), minMetricDescriptor.getUnit(), resultSeries.minSeries);
        }
        if (isMaxEnabled()) {
            ImmutableMetricData.createDoubleGauge(resource, InstrumentationScopeInfo.create(maxMetricDescriptor.getName()), maxMetricDescriptor.getName(), maxMetricDescriptor.getDescription(), maxMetricDescriptor.getUnit(), resultSeries.maxSeries);
        }
        return resultMetrics;
    }

    boolean isMinEnabled() {
        return minMetricDescriptor != null;
    }

    boolean isMaxEnabled() {
        return maxMetricDescriptor != null;
    }

    private void computeSeries(List<String> tagValues, double[] data, long time, ResultSeriesCollector resultSeries) {
        Attributes attributes = toAttributes(tagIndices, tagValues);

        if (isMinEnabled() || isMaxEnabled()) {
            double minValue = Double.MAX_VALUE;
            double maxValue = -Double.MAX_VALUE;
            for (double value : data) {
                minValue = Math.min(minValue, value);
                maxValue = Math.max(maxValue, value);
            }
            if (isMinEnabled()) {
                resultSeries.addMinimum(minValue, time, attributes);
            }
            if (isMaxEnabled()) {
                resultSeries.addMaximum(maxValue, time, attributes);
            }
        }
        if (!percentiles.isEmpty()) {
            Percentile percentileComputer = new Percentile();
            percentileComputer.setData(data);
            for (double percentile : percentiles) {
                double percentileValue = percentileComputer.evaluate(percentile * 100);
                resultSeries.addPercentile(percentileValue, time, attributes, percentile);
            }
        }
    }

    @VisibleForTesting
    static String getPercentileTag(double percentile) {
        return PERCENTILE_TAG_FORMATTER.format(percentile);
    }

    private List<String> getTagsList(Baggage tagContext) {
        String[] tagValues = new String[tagIndices.size()];
        Arrays.fill(tagValues, "");
        tagContext.forEach((s, baggageEntry) -> {
            Integer index = tagIndices.get(s);
            if (index != null) {
                tagValues[index] = baggageEntry.getValue();
            }
        });
        return Arrays.asList(tagValues);
    }

    private Attributes toAttributes(List<String> tagKeys, List<String> tagValues) {
        AttributesBuilder attributesBuilder = Attributes.builder();
        for (int i = 0; i < tagKeys.size(); i++) {
            attributesBuilder.put(AttributeKey.stringKey(tagKeys.get(i)), tagValues.get(tagIndices.get(tagKeys.get(i))));
        }
        return attributesBuilder.build();
    }

    private Attributes toAttributes(Map<String, Integer> tagIndices, List<String> tagValues) {
        AttributesBuilder attributesBuilder = Attributes.builder();
        for (Map.Entry<String, Integer> entry : tagIndices.entrySet()) {
            attributesBuilder.put(AttributeKey.stringKey(entry.getKey()), tagValues.get(entry.getValue()));
        }
        return attributesBuilder.build();
    }

    private List<String> toLabelValues(List<String> tagValues) {
        return tagValues;
    }

    private List<String> toLabelValuesWithPercentile(List<String> tagValues, double percentile) {
        List<String> result = new ArrayList<>(toLabelValues(tagValues));
        result.add(getPercentileTag(percentile));
        return result;
    }

    private List<AttributeKey> getLabelKeysInOrderForPercentiles() {
        AttributeKey[] keys = new AttributeKey[tagIndices.size() + 1];
        tagIndices.forEach((tag, index) -> keys[index] = AttributeKey.stringKey(tag));
        keys[keys.length - 1] = AttributeKey.stringKey(PERCENTILE_TAG_KEY);
        return Arrays.asList(keys);
    }

    private List<AttributeKey> getLabelKeysInOrderForMinMax() {
        AttributeKey[] keys = new AttributeKey[tagIndices.size()];
        tagIndices.forEach((tag, index) -> keys[index] = AttributeKey.stringKey(tag));
        return Arrays.asList(keys);
    }

    private long getInMillis(long timeInNanos) {
        return TimeUnit.MILLISECONDS.toMillis(timeInNanos);
    }

    private class ResultSeriesCollector {

        private GaugeData minSeries = ImmutableGaugeData.create(Collections.emptyList());

        private GaugeData maxSeries = ImmutableGaugeData.create(Collections.emptyList());

        private GaugeData percentileSeries = ImmutableGaugeData.create(Collections.emptyList());

        void addMinimum(double value, long time, Attributes attributes) {
            PointData pt = ImmutableDoublePointData.create(time, time, attributes, value);
            minSeries.getPoints().add(pt);
        }

        void addMaximum(double value, long time, Attributes attributes) {
            PointData pt = ImmutableDoublePointData.create(time, time, attributes, value);
            maxSeries.getPoints().add(pt);
        }

        void addPercentile(double value, long time, Attributes attributes, double percentile) {
            AttributesBuilder combinedAttributesBuilder = attributes.toBuilder();
            attributes.forEach((attributeKey, o) -> combinedAttributesBuilder.put(attributeKey.getKey(), getPercentileTag(percentile)));
            PointData pt = ImmutableDoublePointData.create(time, time, combinedAttributesBuilder.build(), value);
            percentileSeries.getPoints().add(pt);
        }
    }

}
