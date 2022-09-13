package rocks.inspectit.ocelot.core.instrumentation.hook.actions.span;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rocks.inspectit.ocelot.core.instrumentation.context.InspectitContextImpl;
import rocks.inspectit.ocelot.core.instrumentation.hook.actions.IHookAction;

import java.lang.reflect.Field;

import static org.mockito.Mockito.doReturn;

/**
 * Base class for tests that need a mockable {@link Span} that can be registered as the {@link Span#current()} with {@link Span#makeCurrent()}
 */
@ExtendWith(MockitoExtension.class)
public class MockedSpanTestBase {

    @Mock
    IHookAction.ExecutionContext executionContext;

    @Mock
    InspectitContextImpl ctx;

    @Mock
    Span span;

    /**
     * The class of {@link io.opentelemetry.api.trace.SpanContextKey}
     */
    private static final Class<?> SPANCONTEXTKEY_CLASS;

    /**
     * The {@link io.opentelemetry.api.trace.SpanContextKey#KEY} member of {@link io.opentelemetry.api.trace.SpanContextKey}
     */
    private static final Field SPANCONTEXTKEY_KEY;

    /**
     * The {@link io.opentelemetry.api.trace.SpanContextKey} used to store the {@link #span} in the {@link Context}
     */
    private static final ContextKey<Span> SPAN_CONTEXT_KEY;

    static {
        try {
            SPANCONTEXTKEY_CLASS = Class.forName("io.opentelemetry.api.trace.SpanContextKey");
            SPANCONTEXTKEY_KEY = SPANCONTEXTKEY_CLASS.getDeclaredField("KEY");
            SPANCONTEXTKEY_KEY.setAccessible(true);
            SPAN_CONTEXT_KEY = (ContextKey<Span>) SPANCONTEXTKEY_KEY.get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void initMocks() {
        doReturn(ctx).when(executionContext).getInspectitContext();
        // directly call the Context.current().with(ContextKey key, Span span) method, as otherwise the mocked span will not be set to Span.current()
        doReturn(Context.current().with(SPAN_CONTEXT_KEY, span).makeCurrent()).when(span).makeCurrent();
    }

}
