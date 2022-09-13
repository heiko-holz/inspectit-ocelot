package rocks.inspectit.ocelot.core.instrumentation.hook.actions.span;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.*;

public class SetSpanStatusActionTest extends MockedSpanTestBase {

    @Nested
    class Execute {

        @Test
        void nullStatus() {
            SetSpanStatusAction action = new SetSpanStatusAction((ctx) -> null);
            doReturn(true).when(ctx).hasEnteredSpan();

            try (Scope spanScope = span.makeCurrent()) {
                action.execute(executionContext);
            }

            verifyNoMoreInteractions(span);
        }

        @Test
        void falseStatus() {
            SetSpanStatusAction action = new SetSpanStatusAction((ctx) -> false);
            doReturn(true).when(ctx).hasEnteredSpan();

            try (Scope spanScope = span.makeCurrent()) {
                action.execute(executionContext);
            }

            verifyNoMoreInteractions(span);
        }

        @Test
        void throwableStatus() {
            SetSpanStatusAction action = new SetSpanStatusAction((ctx) -> new Throwable());
            doReturn(true).when(ctx).hasEnteredSpan();

            try (Scope spanScope = span.makeCurrent()) {
                action.execute(executionContext);
            }

            verify(span).setStatus(StatusCode.UNSET);
            verify(span).setAttribute(eq(AttributeKey.booleanKey("error")), eq(true));

            verifyNoMoreInteractions(span);
        }

        @Test
        void noSpanEntered() {
            SetSpanStatusAction action = new SetSpanStatusAction((ctx) -> new Throwable());
            doReturn(false).when(ctx).hasEnteredSpan();

            try (Scope spanScope = span.makeCurrent()) {
                action.execute(executionContext);
            }

            verifyNoMoreInteractions(span);
        }

    }

    static class MockableSpan implements Span {

        Span span;

        @Override
        public <T> Span setAttribute(AttributeKey<T> key, T value) {
            return span.setAttribute(key, value);
        }

        @Override
        public Span addEvent(String name, Attributes attributes) {
            return span.addEvent(name, attributes);
        }

        @Override
        public Span addEvent(String name, Attributes attributes, long timestamp, TimeUnit unit) {
            return span.addEvent(name, attributes, timestamp, unit);
        }

        @Override
        public Span setStatus(StatusCode statusCode, String description) {
            return span.setStatus(statusCode, description);
        }

        @Override
        public Span recordException(Throwable exception, Attributes additionalAttributes) {
            return span.recordException(exception, additionalAttributes);
        }

        @Override
        public Span updateName(String name) {
            return span.updateName(name);
        }

        @Override
        public void end() {
            span.end();
        }

        @Override
        public void end(long timestamp, TimeUnit unit) {
            span.end(timestamp, unit);
        }

        @Override
        public SpanContext getSpanContext() {
            return span.getSpanContext();
        }

        @Override
        public boolean isRecording() {
            return span.isRecording();
        }
    }
}
