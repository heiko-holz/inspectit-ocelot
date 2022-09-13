package rocks.inspectit.ocelot.core.metrics.percentiles;

import com.google.common.annotations.VisibleForTesting;
import io.opentelemetry.api.baggage.Baggage;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Consumer thread for asynchronously processing measurement observations.
 */
@Slf4j
class AsyncMetricRecorder {

    private static final int QUEUE_CAPACITY = 8096;

    private final MetricConsumer metricConsumer;

    private volatile boolean overflowLogged = false;

    private volatile boolean isDestroyed = false;

    @VisibleForTesting
    final ArrayBlockingQueue<MetricRecord> recordsQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    @VisibleForTesting
    final Thread worker;

    AsyncMetricRecorder(MetricConsumer consumer) {
        metricConsumer = consumer;
        worker = new Thread(this::doRecord);
        worker.setDaemon(true);
        worker.setName("InspectIT Ocelot percentile Recorder");
        worker.start();
    }

    void record(String measureName, double value, long time, Baggage tags) {
        boolean success = recordsQueue.offer(new MetricRecord(value, measureName, time, tags));
        if (!success && !overflowLogged) {
            overflowLogged = true;
            log.warn("Measurement for percentiles has been dropped because queue is full. This message will not be shown for further drops!");
        }
    }

    void destroy() {
        isDestroyed = true;
        worker.interrupt();
    }

    private void doRecord() {
        while (true) {
            try {
                MetricRecord record = recordsQueue.take();
                metricConsumer.record(record.measure, record.value, record.time, record.tagContext);
            } catch (InterruptedException e) {
                if (isDestroyed) {
                    return;
                } else {
                    log.error("Unexpected interrupt", e);
                }
            } catch (Exception e) {
                log.error("Error processing record: ", e);
            }
        }
    }

    public interface MetricConsumer {

        // void record(String measure, double value, long time, TimeUnit timeUnit, Baggage tags);

        void record(String measure, double value, long time, Baggage tags);

    }

    @Value
    private static class MetricRecord {

        double value;

        String measure;

        long time;

        TimeUnit timeUnit = TimeUnit.MILLISECONDS;

        Baggage tagContext;

    }

}
