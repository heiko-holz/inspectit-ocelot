package rocks.inspectit.ocelot.core.instrumentation.autotracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.IdGenerator;
import lombok.Getter;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A {@link io.opentelemetry.api.trace.Span}, which acts as a placeholder.
 * This span can be activated via {@link Tracer#withSpan(Span)} normally and act as a parent of other spans.
 * <p>
 * However, when {@link #end(EndSpanOptions)} is called, the span is not exported immidately.
 * Instead it will only be exported after both {@link #end(EndSpanOptions)} and {@link #exportWithParent(Span, TimestampConverter)} have been called.
 */
public class PlaceholderSpan implements Span {

    /**
     * Used for generating a span-ids
     */
    private static final Random RANDOM = new Random();

    /**
     * Stores the attributes added to this span.
     */
    private Attributes attributes = Attributes.empty();

    private String spanName;

    private SpanKind spanKind;

    private Supplier<Long> clock;

    private long startTime;

    private long endTime = 0L;

    private Span newParent;

    @Getter
    private SpanContext spanContext;

    private boolean exported = false;

    private TimeUnit timeUnit = TimeUnit.NANOSECONDS;

    private Object anchoredClock;

    PlaceholderSpan(SpanContext defaultParent, String spanName, SpanKind kind, Supplier<Long> clock) {
        spanContext = generateContext(defaultParent);
        //, EnumSet.of(Span.Options.RECORD_EVENTS));
        this.spanName = spanName;
        spanKind = kind;
        this.clock = clock;
        startTime = clock.get();
    }

    private static SpanContext generateContext(SpanContext parentContext) {

        String id = IdGenerator.random().generateSpanId();
        return SpanContext.create(parentContext.getTraceId(), id, parentContext.getTraceFlags(), parentContext.getTraceState());
    }

    @Override
    public Span setAttribute(AttributeKey key, Object value) {
        attributes.toBuilder().put(key, value);
        return this;
    }

    /**
     * Alters the parent of this span. May only be called exactly once.
     *
     * @param newParent     the parent to use
     * @param anchoredClock the timestamp converter to use
     */
    public synchronized void exportWithParent(Span newParent, Object anchoredClock) {

        this.anchoredClock = anchoredClock;
        this.newParent = newParent;
        if (endTime != 0) {
            export();
        }
    }

    private void export() {
        if (!exported) {
            exported = true;
            Span span = CustomSpanBuilder.builder(spanName, newParent)
                    .kind(spanKind)
                    .customTiming(startTime, endTime, anchoredClock)
                    .spanId(getSpanContext().getSpanId())
                    .startSpan();
            span.setAllAttributes(attributes);
            span.end();//endTime, timeUnit);
        }
    }

    public long getStartTime() {
        return startTime;
    }

    public String getSpanName() {
        return spanName;
    }

    @Override
    public Span addEvent(String name, Attributes attributes) {
        // not yet implemented
        return this;
    }

    @Override
    public Span addEvent(String name, Attributes attributes, long timestamp, TimeUnit unit) {
        // not yet implemented
        return this;
    }

    @Override
    public Span setStatus(StatusCode statusCode, String description) {
        // not yet implemented
        return this;
    }

    @Override
    public Span recordException(Throwable exception, Attributes additionalAttributes) {
        // not yet implemented
        return this;
    }

    @Override
    public Span updateName(String name) {
        spanName = name;
        return this;
    }

    @Override
    public void end() {
        endTime = clock.get();
        if (newParent != null) {
            export();
        }
    }

    @Override
    public void end(long timestamp, TimeUnit unit) {
        endTime = timestamp;
        timeUnit = unit;
        if (newParent != null) {
            export();
        }
    }

    @Override
    public boolean isRecording() {
        return false;
    }
}
