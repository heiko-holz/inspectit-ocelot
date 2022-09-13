package rocks.inspectit.ocelot.core.exporter;

import de.flapdoodle.embed.process.runtime.Network;
import io.apisense.embed.influx.InfluxServer;
import io.apisense.embed.influx.configuration.InfluxConfigurationWriter;
import io.github.netmikey.logunit.api.LogCapturer;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.InfluxDBIOException;
import org.influxdb.dto.Query;
import org.influxdb.dto.QueryResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.annotation.DirtiesContext;
import rocks.inspectit.ocelot.core.SpringTestBase;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class InfluxExporterServiceIntTest extends SpringTestBase {

    private InfluxServer influx;

    private String url;

    private static final String DATABASE = "ocelot_test";

    @RegisterExtension
    LogCapturer warnLogs = LogCapturer.create().captureForType(InfluxExporterService.class, org.slf4j.event.Level.WARN);

    @BeforeEach
    void startInfluxDB() throws Exception {
        InfluxServer.Builder builder = new InfluxServer.Builder();
        int freeHttpPort = Network.getFreeServerPort();
        InfluxConfigurationWriter influxConfig = new InfluxConfigurationWriter.Builder().setHttp(freeHttpPort) // by default auth is disabled
                .build();
        builder.setInfluxConfiguration(influxConfig);
        influx = builder.build();
        influx.start();
        url = "http://localhost:" + freeHttpPort;
    }

    @AfterEach
    void shutdownInfluxDB() throws Exception {
        influx.cleanup();
    }

    private final String user = "w00t";

    private final String password = "password";

    @Test
    void verifyInfluxDataWritten() {
        updateProperties(props -> {
            props.setProperty("inspectit.exporters.metrics.influx.enabled", true);
            props.setProperty("inspectit.exporters.metrics.influx.export-interval", "1s");
            props.setProperty("inspectit.exporters.metrics.influx.endpoint", url);
            props.setProperty("inspectit.exporters.metrics.influx.database", DATABASE);
            // note: user and password are mandatory as of v1.15.0
            props.setProperty("inspectit.exporters.metrics.influx.user", user);
            props.setProperty("inspectit.exporters.metrics.influx.password", password);
        });

        recordMetricsAndFlush(20, "my_tag", ",myval");
        recordMetricsAndFlush(22, "my_tag", ",myval");

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            try {
                InfluxDB iDB = InfluxDBFactory.connect(url, user, password); // note: user and password are mandatory as of v1.15.0
                QueryResult result = iDB.query(new Query("SELECT LAST(cool_data) FROM " + DATABASE + ".autogen.my_test_measure GROUP BY *"));

                List<QueryResult.Result> results = result.getResults();
                assertThat(results).hasSize(1);
                QueryResult.Result data = results.get(0);
                assertThat(data.getSeries()).hasSize(1);
                QueryResult.Series series = data.getSeries().get(0);
                assertThat(series.getTags()).hasSize(1).containsEntry("my_tag", "myval");
                assertThat(series.getValues().get(0).get(1)).isEqualTo(42.0);
            } catch (InfluxDBIOException exception) {
                // ignore
            }
        });
    }

    @DirtiesContext
    @Test
    void testNoEndpointSet() {
        updateProperties(props -> {
            props.setProperty("inspectit.exporters.metrics.influx.endpoint", "");
            props.setProperty("inspectit.exporters.metrics.influx.enabled", "ENABLED");
        });
        warnLogs.assertContains("'endpoint'");
    }
}
