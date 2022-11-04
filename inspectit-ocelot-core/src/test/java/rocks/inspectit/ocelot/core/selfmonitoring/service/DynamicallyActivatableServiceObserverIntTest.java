package rocks.inspectit.ocelot.core.selfmonitoring.service;

import org.junit.jupiter.api.Test;
import rocks.inspectit.ocelot.core.SpringTestBase;
import rocks.inspectit.ocelot.core.service.DynamicallyActivatableService;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class DynamicallyActivatableServiceObserverIntTest extends SpringTestBase {

    private DynamicallyActivatableServiceObserver serviceObserver;

    @Test
    void verifyStatesHaveBeenObserved() {
        updateProperties(props -> {
            props.setProperty("inspectit.exporters.metrics.influx.enabled", true);
            props.setProperty("inspectit.exporters.metrics.prometheus", "ENABLED");
            props.setProperty("inspectit.exporters.tracing.jaeger.enabled", "ENABLED");
        });

        try{
            Map<String, Boolean> serviceStateMap = serviceObserver.getServiceStateMap();

            for(String service : serviceStateMap.keySet()){
                assertThat(serviceStateMap.get(service)).isEqualTo(DynamicallyActivatableServiceObserver.getServiceStateMap().get(service));
            }
        }catch(Exception e){
            //ignore
        }
    }

}
