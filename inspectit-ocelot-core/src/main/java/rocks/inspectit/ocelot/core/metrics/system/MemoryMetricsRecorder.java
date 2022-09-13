package rocks.inspectit.ocelot.core.metrics.system;

import io.opentelemetry.api.baggage.Baggage;
import lombok.val;
import org.springframework.stereotype.Service;
import rocks.inspectit.ocelot.config.model.metrics.MetricsSettings;
import rocks.inspectit.ocelot.core.tags.AttributesUtils;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.time.Duration;
import java.util.Map;

@Service
public class MemoryMetricsRecorder extends AbstractPollingMetricsRecorder {

    private static final String USED_METRIC_NAME = "used";

    private static final String USED_METRIC_FULL_NAME = "jvm/memory/used";

    private static final String COMMITTED_METRIC_NAME = "committed";

    private static final String COMMITTED_METRIC_FULL_NAME = "jvm/memory/committed";

    private static final String MAX_METRIC_NAME = "max";

    private static final String MAX_METRIC_FULL_NAME = "jvm/memory/max";

    private static final String BUFFER_COUNT_METRIC_NAME = "buffer.count";

    private static final String BUFFER_COUNT_METRIC_FULL_NAME = "jvm/buffer/count";

    private static final String BUFFER_USED_METRIC_NAME = "buffer.used";

    private static final String BUFFER_USED_METRIC_FULL_NAME = "jvm/buffer/memory/used";

    private static final String BUFFER_CAPACITY_METRIC_NAME = "buffer.capacity";

    private static final String BUFFER_CAPACITY_METRIC_FULL_NAME = "jvm/buffer/total/capacity";

    private String idTagKey = "id";

    private String areaTagKey = "area";

    public MemoryMetricsRecorder() {
        super("metrics.memory");
    }

    @Override
    protected void takeMeasurement(MetricsSettings config) {
        val enabled = config.getMemory().getEnabled();
        recordMemoryMetrics(enabled);
        recordBufferMetrics(enabled);
    }

    @Override
    protected Duration getFrequency(MetricsSettings config) {
        return config.getMemory().getFrequency();
    }

    @Override
    protected boolean checkEnabledForConfig(MetricsSettings ms) {
        return ms.getMemory().getEnabled().containsValue(true);
    }

    private void recordMemoryMetrics(Map<String, Boolean> enabledMetrics) {
        boolean usedEnabled = enabledMetrics.getOrDefault(USED_METRIC_NAME, false);
        boolean committedEnabled = enabledMetrics.getOrDefault(COMMITTED_METRIC_NAME, false);
        boolean maxEnabled = enabledMetrics.getOrDefault(MAX_METRIC_NAME, false);
        if (usedEnabled || committedEnabled || maxEnabled) {
            for (MemoryPoolMXBean memoryPoolBean : ManagementFactory.getPlatformMXBeans(MemoryPoolMXBean.class)) {
                String area = MemoryType.HEAP.equals(memoryPoolBean.getType()) ? "heap" : "nonheap";
                Baggage tags = Baggage.current()
                        .toBuilder()
                        .put(idTagKey, AttributesUtils.createAttributeValue(idTagKey, memoryPoolBean.getName()))
                        .put(areaTagKey, AttributesUtils.createAttributeValue(areaTagKey, area))
                        .build();

                if (usedEnabled) {
                    measureManager.tryRecordingMeasurement(USED_METRIC_FULL_NAME, memoryPoolBean.getUsage()
                            .getUsed(), tags);
                }
                if (committedEnabled) {
                    measureManager.tryRecordingMeasurement(COMMITTED_METRIC_FULL_NAME, memoryPoolBean.getUsage()
                            .getCommitted(), tags);
                }
                if (maxEnabled) {
                    long max = memoryPoolBean.getUsage().getMax();
                    if (max == -1) { //max memory not set
                        max = 0L; //negative values are not supported by OpenCensus
                    }
                    measureManager.tryRecordingMeasurement(MAX_METRIC_FULL_NAME, max, tags);

                }
            }
        }
    }

    private void recordBufferMetrics(Map<String, Boolean> enabledMetrics) {
        boolean bufferCountEnabled = enabledMetrics.getOrDefault(BUFFER_COUNT_METRIC_NAME, false);
        boolean bufferUsedEnabled = enabledMetrics.getOrDefault(BUFFER_USED_METRIC_NAME, false);
        boolean bufferCapacityEnabled = enabledMetrics.getOrDefault(BUFFER_CAPACITY_METRIC_NAME, false);
        if (bufferCountEnabled || bufferUsedEnabled || bufferCapacityEnabled) {
            for (BufferPoolMXBean bufferPoolBean : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
                Baggage tags = Baggage.current()
                        .toBuilder()
                        .put(idTagKey, AttributesUtils.createAttributeValue(idTagKey, bufferPoolBean.getName()))
                        .build();

                if (bufferCountEnabled) {
                    measureManager.tryRecordingMeasurement(BUFFER_COUNT_METRIC_FULL_NAME, bufferPoolBean.getCount(), tags);
                }
                if (bufferUsedEnabled) {
                    measureManager.tryRecordingMeasurement(BUFFER_USED_METRIC_FULL_NAME, bufferPoolBean.getMemoryUsed(), tags);
                }
                if (bufferCapacityEnabled) {
                    measureManager.tryRecordingMeasurement(BUFFER_CAPACITY_METRIC_FULL_NAME, bufferPoolBean.getTotalCapacity(), tags);
                }
            }
        }
    }
}
