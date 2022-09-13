package rocks.inspectit.ocelot.core.instrumentation.hook.actions.span;

import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import rocks.inspectit.ocelot.core.privacy.obfuscation.IObfuscatory;

import static org.mockito.Mockito.*;

public class WriteSpanAttributesTest extends MockedSpanTestBase {

    @Mock
    IObfuscatory obfuscatory;

    @Nested
    class Execute {

        @Test
        void verifyNoAttributesWrittenIfNoSpanStarted() {
            doReturn(false).when(ctx).hasEnteredSpan();
            WriteSpanAttributesAction action = WriteSpanAttributesAction.builder()
                    .obfuscatorySupplier(() -> obfuscatory)
                    .attributeAccessor("foo", (exec) -> "bar")
                    .build();

            try (Scope s = span.makeCurrent()) {
                action.execute(executionContext);
            }

            verifyNoMoreInteractions(obfuscatory);
        }

        @Test
        void verifyAttributesWritten() {
            doReturn(true).when(ctx).hasEnteredSpan();
            WriteSpanAttributesAction action = WriteSpanAttributesAction.builder()
                    .obfuscatorySupplier(() -> obfuscatory)
                    .attributeAccessor("foo", (exec) -> "bar")
                    .attributeAccessor("hello", (exec) -> 2.0d)
                    .attributeAccessor("iAmNull", (exec) -> null)
                    .build();

            try (Scope s = span.makeCurrent()) {
                action.execute(executionContext);
            }

            verify(obfuscatory).putSpanAttribute(same(span), eq("foo"), eq("bar"));
            verify(obfuscatory).putSpanAttribute(same(span), eq("hello"), eq(Double.valueOf(2.0d)));
            verifyNoMoreInteractions(obfuscatory);
        }

    }
}
