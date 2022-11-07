package rocks.inspectit.ocelot.core.selfmonitoring.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import rocks.inspectit.ocelot.config.model.exporters.ExporterEnabledState;
import rocks.inspectit.ocelot.config.model.exporters.TransportProtocol;
import rocks.inspectit.ocelot.core.SpringTestBase;
import rocks.inspectit.ocelot.core.exporter.JaegerExporterService;
import rocks.inspectit.ocelot.core.exporter.OtlpMetricsExporterService;
import rocks.inspectit.ocelot.core.exporter.PrometheusExporterService;
import rocks.inspectit.ocelot.core.service.DynamicallyActivatableService;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class DynamicallyActivatableServiceObserverIntTest extends SpringTestBase {

    @Autowired
    private DynamicallyActivatableServiceObserver serviceObserver;

    @Autowired
    private JaegerExporterService jaegerService;
    @Autowired
    PrometheusExporterService prometheusExporterService;

    @Autowired
    OtlpMetricsExporterService otlpMetricsExporterService;

    private Map<String, Boolean> expectedServiceStates;

    @Test
    @DirtiesContext
    void verifyStatesHaveBeenObserved() {
        updateProperties(props -> {
            props.setProperty("inspectit.exporters.metrics.influx.enabled", true);
            props.setProperty("inspectit.exporters.metrics.prometheus.enabled", ExporterEnabledState.ENABLED);
            props.setProperty("inspectit.exporters.tracing.jaeger.enabled", ExporterEnabledState.ENABLED);
            props.setProperty("inspectit.exporters.tracing.jaeger.endpoint", "http://localhost:14250/api/traces");
            props.setProperty("inspectit.exporters.tracing.jaeger.protocol", TransportProtocol.GRPC);

        });
        assertThat(jaegerService.isEnabled()).isTrue();

        expectedServiceStates = new HashMap<>();
        expectedServiceStates.put(jaegerService.getName(), true);
        expectedServiceStates.put(prometheusExporterService.getName(), true);
        expectedServiceStates.put(otlpMetricsExporterService.getName(), false);

        assertExpectedServices();

        // update properties, check if the update gets observed
        updateProperties(props -> {
            props.setProperty("inspectit.exporters.tracing.jaeger.enabled", ExporterEnabledState.DISABLED);
        });
        expectedServiceStates.put(jaegerService.getName(), false);
        assertExpectedServices();
    }

    void assertExpectedServices(){
        try{
            Map<String, Boolean> serviceStateMap = serviceObserver.getServiceStateMap();
            for (String serviceName : expectedServiceStates.keySet()) {
                assertThat(serviceStateMap.get(serviceName)).isEqualTo(expectedServiceStates.get(serviceName));
            }

        }catch(Exception e){
            //ignore
        }
    }
}
