package rocks.inspectit.ocelot.core.tags;

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Test;
import rocks.inspectit.ocelot.core.SpringTestBase;

import static org.assertj.core.api.Assertions.assertThat;

public class TagUtilsTest extends SpringTestBase {

    @Test
    public void createTagValue() {
        assertThat(AttributesUtils.createAttributeValue("my-tag-key", "my-tag-value")).isEqualTo("my-tag-value");
    }

    @Test
    public void createTagValue_tooLong() {
        assertThat(AttributesUtils.createAttributeValue("my-tag-key", "this-value-is-over-255-characters-long ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------")).isEqualTo("<invalid>");
    }

    @Test
    public void createTagValue_nonPrintableCharacter() {
        assertThat(AttributesUtils.createAttributeValue("my-tag-key", "non-printable-character-\u007f")).isEqualTo("<invalid>");
    }

    @Test
    public void multipleCreateTagValue_nonPrintableCharacter() {
        AttributesUtils.printedWarningCounter = 0;

        for (int i = 0; i < 11; i++) {
            AttributesUtils.createAttributeValue("my-tag-key", "non-printable-character-\u007f");
        }
        assertLogsOfLevelOrGreater(Level.WARN);
        assertLogCount("Error creating value for tag", 10);
    }

    @Test
    public void multipleCreateTagValue_moreThan10Minutes() {
        AttributesUtils.printedWarningCounter = 0;

        for (int i = 0; i < 11; i++) {
            AttributesUtils.createAttributeValue("my-tag-key", "non-printable-character-\u007f");
        }

        AttributesUtils.lastWarningTime = AttributesUtils.lastWarningTime - 610000;
        AttributesUtils.createAttributeValue("my-tag-key", "non-printable-character-\u007f");

        assertLogCount("Error creating value for tag", 11);
    }
}
