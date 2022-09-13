package rocks.inspectit.ocelot.core.metrics;

import com.google.common.annotations.VisibleForTesting;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleGaugeBuilder;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.opentelemetry.api.metrics.ObservableMeasurement;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.View;
import io.opentelemetry.sdk.metrics.internal.descriptor.InstrumentDescriptor;
import io.opentelemetry.sdk.metrics.internal.view.AttributesProcessor;
import io.opentelemetry.sdk.metrics.internal.view.ExplicitBucketHistogramAggregation;
import io.opentelemetry.sdk.metrics.internal.view.LastValueAggregation;
import io.opentelemetry.sdk.metrics.internal.view.SumAggregation;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import rocks.inspectit.ocelot.config.model.metrics.MetricsSettings;
import rocks.inspectit.ocelot.config.model.metrics.definition.MetricDefinitionSettings;
import rocks.inspectit.ocelot.config.model.metrics.definition.ViewDefinitionSettings;
import rocks.inspectit.ocelot.core.config.InspectitConfigChangedEvent;
import rocks.inspectit.ocelot.core.config.InspectitEnvironment;
import rocks.inspectit.ocelot.core.metrics.percentiles.PercentileViewManager;
import rocks.inspectit.ocelot.core.opentelemetry.OpenTelemetryControllerImpl;
import rocks.inspectit.ocelot.core.tags.AttributesUtils;
import rocks.inspectit.ocelot.core.tags.CommonTagsManager;
import rocks.inspectit.ocelot.core.utils.OpenTelemetryUtils;

import javax.annotation.PostConstruct;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * This class is responsible for creating and caching OpenCensus views and measures
 * based on what is defined in inspectit.metrics.definitions.
 */
@Component
@Slf4j
public class MeasuresAndViewsManager {

    @Autowired
    private CommonTagsManager commonTags;

    @Autowired
    private PercentileViewManager percentileViewManager;

    @Autowired
    private InspectitEnvironment env;

    @Autowired
    private OpenTelemetryControllerImpl openTelemetryController;

    /**
     * Caches all created measures.
     */
    private final ConcurrentHashMap<String, ObservableMeasurement> cachedMeasures = new ConcurrentHashMap<>();

    /**
     * Caches the definition which was used to build the measures and views for a given metric.
     * This is used to quickly detect which metrics have changed on configuration updates.
     */
    private final Map<String, MetricDefinitionSettings> currentMetricDefinitionSettings = new HashMap<>();

    /**
     * The {@link ExplicitBucketHistogramAggregation#bucketBoundaries} member of {@link ExplicitBucketHistogramAggregation}
     */
    private static final Field EXPLICITBUCKETHISTOGRAMAGGREGATION_BUCKETBOUNDARIES;

    /**
     * The {@link View#getAttributesProcessor()} member of {@link View}
     */
    private static final Method VIEW_GETATTRIBUTESPROCESSOR;

    static {
        try {
            EXPLICITBUCKETHISTOGRAMAGGREGATION_BUCKETBOUNDARIES = ExplicitBucketHistogramAggregation.class.getDeclaredField("bucketBoundaries");
            EXPLICITBUCKETHISTOGRAMAGGREGATION_BUCKETBOUNDARIES.setAccessible(true);
            VIEW_GETATTRIBUTESPROCESSOR = View.class.getDeclaredMethod("getAttributesProcessor");
            VIEW_GETATTRIBUTESPROCESSOR.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * If a measure with the given name is defined via {@link MetricsSettings#getDefinitions()},
     * it is returned by this method.
     * You should not cache the result of this method to make sure that dynamic updates are not missed.
     *
     * @param name the name of the measure (=the name of the {@link MetricDefinitionSettings}
     *
     * @return the measure if it is registered, an empty optional otherwise
     */
    public Optional<ObservableMeasurement> getMeasure(String name) {
        return Optional.ofNullable(cachedMeasures.get(name));
    }

    /**
     * If a measure with type long an given name is defined via {@link MetricsSettings#getDefinitions()},
     * it is returned by this method.
     * You should not cache the result of this method to make sure that dynamic updates are not missed.
     *
     * @param name the name of the measure (=the name of the {@link MetricDefinitionSettings}
     *
     * @return the measure if it is registered and has type long, an empty optional otherwise
     */
    public Optional<ObservableLongMeasurement> getMeasureLong(String name) {
        val measure = cachedMeasures.get(name);
        if (measure instanceof ObservableLongMeasurement) {
            return Optional.of((ObservableLongMeasurement) measure);
        }
        return Optional.empty();
    }

    /**
     * If a measure with type double an given name is defined via {@link MetricsSettings#getDefinitions()},
     * it is returned by this method.
     * You should not cache the result of this method to make sure that dynamic updates are not missed.
     *
     * @param name the name of the measure (=the name of the {@link MetricDefinitionSettings}
     *
     * @return the measure if it is registered and has type double, an empty optional otherwise
     */
    public Optional<ObservableDoubleMeasurement> getMeasureDouble(String name) {
        val measure = cachedMeasures.get(name);
        if (measure instanceof ObservableDoubleMeasurement) {
            return Optional.of((ObservableDoubleMeasurement) measure);
        }
        return Optional.empty();
    }

    /**
     * Records a measurement for the given measure, if it exists.
     * Depending on the measure type either {@link Number#doubleValue()}
     * or {@link Number#longValue()} is used.
     *
     * @param measureName the name of the measure
     * @param value       the measurement value for this measure
     */
    public void tryRecordingMeasurement(String measureName, Number value) {
        tryRecordingMeasurement(measureName, value, Baggage.current());
    }

    public void tryRecordingMeasurement(String measureName, Number value, Baggage baggage) {
        val measure = getMeasure(measureName);
        if (measure.isPresent()) {
            val m = measure.get();
            if (m instanceof ObservableLongMeasurement) {
                ((ObservableLongMeasurement) m).record(value.longValue(), AttributesUtils.fromBaggage(baggage));
            } else if (m instanceof ObservableDoubleMeasurement) {
                ((ObservableDoubleMeasurement) m).record(value.doubleValue(), AttributesUtils.fromBaggage(baggage));
            }
        }
        percentileViewManager.recordMeasurement(measureName, value.doubleValue(), baggage);
    }

    /**
     * Creates the measures and views defined via {@link MetricsSettings#getDefinitions()}.
     * OpenCensus does currently not allow the removal of views, therefore updating metrics is not possible.
     */
    @EventListener(InspectitConfigChangedEvent.class)
    @Order(CommonTagsManager.CONFIG_EVENT_LISTENER_ORDER_PRIORITY + 1) //to ensure common tags are updated first
    @PostConstruct
    public void updateMetricDefinitions() {
        MetricsSettings metricsSettings = env.getCurrentConfig().getMetrics();
        if (metricsSettings.isEnabled()) {
            val newMetricDefinitions = metricsSettings.getDefinitions();

            newMetricDefinitions.forEach((name, def) -> {
                // TODO: check if that works!
                // transform from OC to OTEL (replacing '/' by '.')
                name = name.replaceAll("\\/", ".");
                val defWithDefaults = def.getCopyWithDefaultsPopulated(name, metricsSettings.getFrequency());
                val oldDef = currentMetricDefinitionSettings.get(name);
                if (defWithDefaults.isEnabled() && !defWithDefaults.equals(oldDef)) {
                    addOrUpdateAndCacheMeasureWithViews(name, defWithDefaults);
                }
            });
        }
        //TODO: delete removed measures and views as soon as this is possible in Open-Census
    }

    /**
     * Tries to create a measure based on the given definition, with checking measures and views reported by {@link #viewManager}.
     * <p>
     * If the measure or a view already exists, info messages are printed out
     *
     * @param measureName the name of the measure
     * @param definition  the definition of the measure and its views. The defaults must be already populated using {@link MetricDefinitionSettings#getCopyWithDefaultsPopulated(String)}!
     *
     * @see #addOrUpdateAndCacheMeasureWithViews(String, MetricDefinitionSettings, Map, Map)
     */
    public void addOrUpdateAndCacheMeasureWithViews(String measureName, MetricDefinitionSettings definition) {

        Map<String, View> registeredViews = openTelemetryController.getRegisteredViews()
                .values()
                .stream()
                .collect(Collectors.toMap(View::getName, view -> view));

        Map<String, ObservableMeasurement> registeredMeasures = new HashMap<>();
        openTelemetryController.getRegisteredViews()
                .keySet()
                .stream()
                .distinct()
                .forEach(observableMeasurement -> registeredMeasures.put(OpenTelemetryUtils.getInstrumentDescriptor(observableMeasurement)
                        .getName(), observableMeasurement));

        addOrUpdateAndCacheMeasureWithViews(measureName, definition, registeredMeasures, registeredViews);

    }

    /**
     * Tries to create a measure based on the given definition as well as its views.
     * If the measure or a view already exists, info messages are printed out
     *
     * @param measureName        the name of the measure
     * @param definition         the definition of the measure and its views. The defaults
     *                           must be already populated using {@link MetricDefinitionSettings#getCopyWithDefaultsPopulated(String)}!
     * @param registeredMeasures a map of which measures are already registered at the OpenCensus API. Maps the names to the measures. Used to detect if the measure to create already exists.
     * @param registeredViews    same as registeredMeasures, but maps the view names to the corresponding registered views.
     */
    @VisibleForTesting
    void addOrUpdateAndCacheMeasureWithViews(String measureName, MetricDefinitionSettings definition, Map<String, ObservableMeasurement> registeredMeasures, Map<String, View> registeredViews) {
        try {
            ObservableMeasurement measure = registeredMeasures.get(measureName);
            if (measure != null) {
                updateMeasure(measureName, measure, definition);
            } else {
                measure = createNewMeasure(measureName, definition);
            }
            ObservableMeasurement resultMeasure = measure;

            Map<String, ViewDefinitionSettings> metricViews = definition.getViews();
            metricViews.forEach((name, view) -> {
                // convert measure name from OC to OTEL by replacing '/' by '.'
                name = name.replaceAll("\\/", ".");
                if (view.isEnabled()) {
                    try {
                        addAndRegisterOrUpdateView(name, resultMeasure, view, registeredViews);
                    } catch (Exception e) {
                        log.error("Error creating view '{}'!", name, e);
                    }
                }
            });

            //TODO: delete views which where created by this class but have been removed from the given metric as soon as OpenCensus supports it
            currentMetricDefinitionSettings.put(measureName, definition);
            cachedMeasures.put(measureName, measure);

        } catch (Exception e) {
            log.error("Error creating metric", e);
        }
    }

    private ObservableMeasurement createNewMeasure(String measureName, MetricDefinitionSettings fullDefinition) {
        ObservableMeasurement measure;

        // FIXME: this does not work currently as we will always get a DefaultMeter (Noop), as no MetricReaders are registered at the SdkMeterProvider
        // maybe the cause is that our OpenTelemetryControllerImpl creates the OTEL resources (tracer, meter etc.) AFTER the views are initialized
        DoubleGaugeBuilder measurementBuilder = OpenTelemetryUtils.getMeter()
                .gaugeBuilder(measureName)
                .setDescription(fullDefinition.getDescription())
                .setUnit(fullDefinition.getUnit());

        switch (fullDefinition.getType()) {
            case LONG:
                measure = measurementBuilder.ofLongs().buildObserver();
                break;
            case DOUBLE:
                measure = measurementBuilder.buildObserver();
                break;
            default:
                throw new RuntimeException("Unhandled measure type: " + fullDefinition.getType());
        }
        return measure;
    }

    private void updateMeasure(String measureName, ObservableMeasurement measure, MetricDefinitionSettings fullDefinition) {
        InstrumentDescriptor instrumentDescriptor = OpenTelemetryUtils.getInstrumentDescriptor(measure);
        if (!fullDefinition.getDescription().equals(instrumentDescriptor.getDescription())) {
            log.warn("Cannot update description of measure '{}' because it has been already registered in OpenCensus!", measureName);
        }
        if (!fullDefinition.getUnit().equals(instrumentDescriptor.getUnit())) {
            log.warn("Cannot update unit of measure '{}' because it has been already registered in OpenCensus!", measureName);
        }
        if ((measure instanceof ObservableLongMeasurement && fullDefinition.getType() != MetricDefinitionSettings.MeasureType.LONG) || (measure instanceof ObservableDoubleMeasurement && fullDefinition.getType() != MetricDefinitionSettings.MeasureType.DOUBLE)) {
            log.warn("Cannot update type of measure '{}' because it has been already registered in OpenCensus!", measureName);
        }
    }

    /**
     * Creates a view if it does not exist yet.
     * Otherwise, prints info messages indicating that updating the view is not possible.
     *
     * @param viewName        the name of the view
     * @param measure         the measure which is used for the view
     * @param def             the definition of the view, on which
     *                        {@link ViewDefinitionSettings#getCopyWithDefaultsPopulated(String, String, String)} was already called.
     * @param registeredViews a map of which views are already registered at the OpenCensus API. Maps the view names to the views.
     */
    private void addAndRegisterOrUpdateView(String viewName, ObservableMeasurement measure, ViewDefinitionSettings def, Map<String, View> registeredViews) {
        InstrumentDescriptor instrumentDescriptor = OpenTelemetryUtils.getInstrumentDescriptor(measure);
        View view = registeredViews.get(viewName);
        if (view != null) {
            updateOpenTelemetryView(viewName, def, view);
        } else {
            if (percentileViewManager.isViewRegistered(instrumentDescriptor.getName(), viewName) || def.getAggregation() == ViewDefinitionSettings.Aggregation.QUANTILES) {
                addOrUpdatePercentileView(measure, viewName, def);
            } else {
                registerNewView(viewName, measure, def);
            }
        }
    }

    private void addOrUpdatePercentileView(ObservableMeasurement measure, String viewName, ViewDefinitionSettings def) {
        InstrumentDescriptor instrumentDescriptor = OpenTelemetryUtils.getInstrumentDescriptor(measure);
        if (def.getAggregation() != ViewDefinitionSettings.Aggregation.QUANTILES) {
            log.warn("Cannot switch aggregation type for View '{}' from QUANTILES to {}", viewName, def.getAggregation());
            return;
        }
        Set<String> viewTags = getTagKeysForView(def);
        boolean minEnabled = def.getQuantiles().contains(0.0);
        boolean maxEnabled = def.getQuantiles().contains(1.0);
        List<Double> percentilesFiltered = def.getQuantiles()
                .stream()
                .filter(p -> p > 0 && p < 1)
                .collect(Collectors.toList());
        percentileViewManager.createOrUpdateView(instrumentDescriptor.getName(), viewName, instrumentDescriptor.getUnit(), def.getDescription(), minEnabled, maxEnabled, percentilesFiltered, def.getTimeWindow()
                .toMillis(), viewTags, def.getMaxBufferedPoints());
    }

    private void registerNewView(String viewName, ObservableMeasurement measure, ViewDefinitionSettings def) {
        Set<String> viewTags = getTagKeysForView(def);
        View view = View.builder()
                .setName(viewName)
                .setAggregation(createAggregation(def))
                .setDescription(def.getDescription())
                .setAttributeFilter(s -> viewTags.contains(s))
                .build();

        openTelemetryController.registerView(measure, view);
    }

    private void updateOpenTelemetryView(String viewName, ViewDefinitionSettings def, View view) {
        if (!def.getDescription().equals(view.getDescription())) {
            log.warn("Cannot update description of view '{}' because it has been already registered in OpenCensus!", viewName);
        }
        if (!isAggregationEqual(view.getAggregation(), def)) {
            log.warn("Cannot update aggregation of view '{}' because it has been already registered in OpenCensus!", viewName);
        }

        Set<String> viewTags = getTagKeysForView(def);

        // TODO: get attributes of view
        // convert the tags to Attributes
        AttributesBuilder viewAttributesBuilder = Attributes.builder();
        viewTags.forEach(tag -> viewAttributesBuilder.put(AttributeKey.booleanKey(tag), Boolean.TRUE));
        Attributes viewAttributes = viewAttributesBuilder.build();

        // get attributes from the definitions that 'survive' the AttributesProcessor of the view, i.e., they pass the filter
        Attributes presentAttributes = getAttributesProcessor(view).process(viewAttributes, Context.current());

        presentAttributes.forEach((attributeKey, o) -> {
            if (!viewTags.contains(attributeKey)) {
                log.warn("Cannot remove tag '{}' from view '{}' because it has been already registered in OpenTelemetry", attributeKey, viewName);
            }
        });
        viewTags.stream()
                .filter(t -> !Boolean.TRUE.equals(presentAttributes.get(AttributeKey.booleanKey(t))))
                .forEach(tag -> log.warn("Cannot add tag '{}' to view '{}' because it has been already registered in OpenTelemetry!", tag, viewName));
    }

    /**
     * Collects the tags which should be used for the given view.
     * This function includes the common tags if requested,
     * then applies the {@link ViewDefinitionSettings#getTags()}.
     *
     * @param def the view definition for which the tags should be collected.
     *
     * @return the set of tag keys
     */
    private Set<String> getTagKeysForView(ViewDefinitionSettings def) {
        Set<String> viewTags = new HashSet<>();
        if (def.isWithCommonTags()) {
            commonTags.getCommonTagKeys()
                    .stream()
                    .filter(t -> def.getTags().get(t) != Boolean.FALSE)
                    .forEach(viewTags::add);
        }
        def.getTags().entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).forEach(viewTags::add);
        return viewTags;
    }

    private boolean isAggregationEqual(Aggregation instance, ViewDefinitionSettings view) {
        switch (view.getAggregation()) {
            case COUNT:
                return instance instanceof SumAggregation;
            case SUM:
                return instance instanceof SumAggregation;
            case HISTOGRAM:
                return instance instanceof ExplicitBucketHistogramAggregation && getBucketBoundaries((ExplicitBucketHistogramAggregation) instance).equals(view.getBucketBoundaries());
            case LAST_VALUE:
                return instance instanceof LastValueAggregation;
            default:
                throw new RuntimeException("Unhandled aggregation type: " + view.getAggregation());
        }
    }

    private Aggregation createAggregation(ViewDefinitionSettings view) {
        switch (view.getAggregation()) {
            case COUNT:
                // apparently, counter uses sum aggregation, see https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/metrics/sdk.md#default-aggregation
                return Aggregation.sum();
            case SUM:
                return Aggregation.sum();
            case HISTOGRAM:
                return Aggregation.explicitBucketHistogram(view.getBucketBoundaries());
            case LAST_VALUE:
                return Aggregation.lastValue();
            default:
                throw new RuntimeException("Unhandled aggregation type: " + view.getAggregation());
        }
    }

    /**
     * Gets the {@link ExplicitBucketHistogramAggregation#bucketBoundaries} for a given {@link ExplicitBucketHistogramAggregation}
     *
     * @param histogramAggregation
     *
     * @return
     */
    private static List<Double> getBucketBoundaries(ExplicitBucketHistogramAggregation histogramAggregation) {
        try {
            return (List<Double>) EXPLICITBUCKETHISTOGRAMAGGREGATION_BUCKETBOUNDARIES.get(histogramAggregation);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Cannot extract bucketBoundaries", e);
        }
    }

    /**
     * Gets the {@link AttributesProcessor} for a given {@link View}
     *
     * @param view
     *
     * @return
     */
    private static AttributesProcessor getAttributesProcessor(View view) {
        try {
            return (AttributesProcessor) VIEW_GETATTRIBUTESPROCESSOR.invoke(view);
        } catch (Exception e) {
            throw new RuntimeException("Cannot extract attributesProcessor", e);
        }
    }
}
