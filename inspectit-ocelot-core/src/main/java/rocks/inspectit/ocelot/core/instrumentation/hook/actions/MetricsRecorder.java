package rocks.inspectit.ocelot.core.instrumentation.hook.actions;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import rocks.inspectit.ocelot.core.instrumentation.context.InspectitContextImpl;
import rocks.inspectit.ocelot.core.instrumentation.hook.actions.model.MetricAccessor;
import rocks.inspectit.ocelot.core.metrics.MeasuresAndViewsManager;
import rocks.inspectit.ocelot.core.tags.AttributesUtils;
import rocks.inspectit.ocelot.core.tags.CommonTagsManager;

import java.util.List;
import java.util.Optional;

/**
 * Hook action responsible for recording measurements at the exit of an instrumented method
 */
@Value
@Slf4j
public class MetricsRecorder implements IHookAction {

    /**
     * A list of metric accessors which will be used to find the value and tags for the metric.
     */
    private final List<MetricAccessor> metrics;

    /**
     * Common tags manager needed for gathering common tags when recording metrics.
     */
    private CommonTagsManager commonTagsManager;

    /**
     * The manager to acquire the actual OpenCensus metrics from
     */
    private MeasuresAndViewsManager metricsManager;

    @Override
    public void execute(ExecutionContext context) {
        // then iterate all metrics and enter new scope for metric collection
        for (MetricAccessor metricAccessor : metrics) {
            Object value = metricAccessor.getVariableAccessor().get(context);
            if (value instanceof Number) {
                // only record metrics where a value is present
                // this allows to disable the recording of a metric depending on the results of action executions
                Baggage tagContext = getTagContext(context, metricAccessor);
                metricsManager.tryRecordingMeasurement(metricAccessor.getName(), (Number) value, tagContext);
            }
        }
    }

    private Baggage getTagContext(ExecutionContext context, MetricAccessor metricAccessor) {
        InspectitContextImpl inspectitContext = context.getInspectitContext();

        // create builder
        BaggageBuilder builder = Baggage.builder();

        // first common tags to allow overwrite by constant or data tags
        commonTagsManager.getCommonTagKeys()
                .forEach(commonTagKey -> Optional.ofNullable(inspectitContext.getData(commonTagKey))
                        .ifPresent(value -> builder.put(commonTagKey, value.toString())));

        // then constant tags to allow overwrite by data
        metricAccessor.getConstantTags()
                .forEach((key, value) -> builder.put(key, AttributesUtils.createAttributeValue(key, value)));

        // go over data tags and match the value to the key from the contextTags (if available)
        metricAccessor.getDataTagAccessors()
                .forEach((key, accessor) -> Optional.ofNullable(accessor.get(context))
                        .ifPresent(tagValue -> builder.put(key, AttributesUtils.createAttributeValue(key, tagValue.toString()))));

        // build and return
        return builder.build();
    }

    @Override
    public String getName() {
        return "Metrics Recorder";
    }
}
