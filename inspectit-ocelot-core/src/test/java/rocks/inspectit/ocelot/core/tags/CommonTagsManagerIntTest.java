package rocks.inspectit.ocelot.core.tags;

import io.opentelemetry.api.baggage.Baggage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import rocks.inspectit.ocelot.core.SpringTestBase;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommonTagsManagerIntTest {

    @Nested
    @DirtiesContext
    class Defaults extends SpringTestBase {

        @Autowired
        CommonTagsManager provider;

        public void contextAvailable() {
            Baggage commonTagContext = provider.getCommonBaggage();

            assertThat(commonTagContext.isEmpty()).isFalse();
        }

        public void tagKeysCorrect() {
            Baggage commonTagContext = provider.getCommonBaggage();
            List<String> commonTagKeys = provider.getCommonTagKeys();

            assertThat(commonTagContext.asMap()).allSatisfy((key, baggageEntry) -> assertThat(commonTagKeys.contains(key)).isTrue());
        }

        public void scopeAvailable() {
            assertThat(provider.withCommonTagScope()).isNotNull();
        }
    }

    @Nested
    @DirtiesContext
    @TestPropertySource(properties = {"inspectit.tags.extra.service-name=my-service-name"})
    class PriorityRespected extends SpringTestBase {

        @Autowired
        CommonTagsManager provider;

        @Test
        public void extraOverwritesProviders() {
            Baggage commonTagContext = provider.getCommonBaggage();
            assertThat(commonTagContext.asMap()).hasEntrySatisfying("service-name", baggageEntry -> assertThat(baggageEntry.getValue()).isEqualTo("my-service-name"));
        }
    }

    @Nested
    @DirtiesContext
    @TestPropertySource(properties = {"inspectit.tags.extra.service-name=my-service-name"})
    class Updates extends SpringTestBase {

        @Autowired
        CommonTagsManager provider;

        @Test
        public void extraOverwritesProviders() {
            updateProperties(properties -> properties.withProperty("inspectit.tags.providers.environment.resolve-host-address", Boolean.FALSE)
                    .withProperty("inspectit.tags.providers.environment.resolve-host-name", Boolean.FALSE)
                    .withProperty("inspectit.service-name", "some-service-name")
                    .withProperty("inspectit.tags.extra.service-name", "my-service-name"));

            Baggage commonTagContext = provider.getCommonBaggage();

            assertThat(commonTagContext.asMap()).hasEntrySatisfying("service-name", baggageEntry -> assertThat(baggageEntry.getValue()).isEqualTo("my-service-name"))
                    .allSatisfy((s, baggageEntry) -> assertThat(s).isNotIn("host", "host-address"));
        }
    }

    @Nested
    @DirtiesContext
    @TestPropertySource(properties = {"inspectit.tags.extra.service-name=this-value-is-over-255-characters-long ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------", "inspectit.tags.extra.service-name2=non-printable-character-\u007f"})
    class VeryLongTagValues extends SpringTestBase {

        @Autowired
        CommonTagsManager provider;

        @Test
        public void extraOverwritesProviders() {
            Baggage commonTagContext = provider.getCommonBaggage();

            assertThat(commonTagContext.asMap()).hasEntrySatisfying("service-name", baggageEntry -> assertThat(baggageEntry.getValue()).isEqualTo("<invalid>"));

        }
    }

}