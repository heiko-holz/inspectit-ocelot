package rocks.inspectit.ocelot.core.tags;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.context.Scope;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import rocks.inspectit.ocelot.config.model.InspectitConfig;
import rocks.inspectit.ocelot.core.config.InspectitConfigChangedEvent;
import rocks.inspectit.ocelot.core.config.InspectitEnvironment;

import javax.annotation.PostConstruct;
import java.util.*;

/**
 * Component that provides tags that should be considered as common and used when ever a metric is recorded.
 */
@Component
@Slf4j
public class CommonTagsManager {

    /**
     * Defines with which @{@link Order} the event listener for updating the common tags in reaction to an updated configuration is executed.
     * The Common tags manager defines highest precedence to ensure that all other registered listeners have access to the updated tags.
     */
    public static final int CONFIG_EVENT_LISTENER_ORDER_PRIORITY = Ordered.HIGHEST_PRECEDENCE;

    @Autowired
    private InspectitEnvironment env;

    /**
     * All {@link ICommonTagsProvider}s registered in the manager.
     */
    @Autowired
    private List<ICommonTagsProvider> providers;

    /**
     * All common tags a simple String map.
     */
    @Getter
    private Map<String, String> commonTagValueMap = Collections.emptyMap();

    /**
     * OpenCensus tag context representing common tag context.
     */
    @Getter
    private Baggage commonBaggage = Baggage.empty();

    /**
     * List of common tag keys that can be used when creating the views.
     */
    private List<String> commonTagKeys = Collections.emptyList();

    /**
     * Returns common tags keys that all view should register.
     *
     * @return Returns common tags keys that all view should register.
     */
    public List<String> getCommonTagKeys() {
        return Collections.unmodifiableList(commonTagKeys);
    }

    /**
     * Returns newly created scope with common tag context. Metrics collectors should use this Scope with the try/resource block:
     * <code>
     * try (Scope scope = withCommonTagScope()) {
     * Stats.getStatsRecorder().newMeasureMap().put(M_ERRORS, 1L).record();
     * }
     * </code>
     *
     * @return Returns newly created scope with default tag context.
     */
    public Scope withCommonTagScope() {
        return commonBaggage.makeCurrent();
    }

    /**
     * Returns newly created scope with common tag context including the additional given tags. Metrics collectors should use this Scope with the try/resource block:
     * <code>
     * try (Scope scope = withCommonTagScope()) {
     * Stats.getStatsRecorder().newMeasureMap().put(M_ERRORS, 1L).record();
     * }
     * </code>
     *
     * @param customTagMap Map with additional tags.
     *
     * @return Returns newly created scope with default tag context including the additional given tags.
     */
    public Scope withCommonTagScope(Map<String, String> customTagMap) {
        if (CollectionUtils.isEmpty(customTagMap)) {
            return withCommonTagScope();
        }

        BaggageBuilder baggageBuilder = Baggage.current().toBuilder();
        HashMap<String, String> tags = new HashMap<>(commonTagValueMap);
        tags.putAll(customTagMap);
        tags.entrySet()
                .stream()
                .forEach(entry -> baggageBuilder.put(entry.getKey(), AttributesUtils.createAttributeValue(entry.getKey(), entry.getValue())));
        return baggageBuilder.build().makeCurrent();
    }

    /**
     * Processes all {@link #providers} and creates common context based on the providers priority.
     */
    @EventListener(InspectitConfigChangedEvent.class)
    @Order(CONFIG_EVENT_LISTENER_ORDER_PRIORITY)
    @PostConstruct
    private void update() {

        InspectitConfig configuration = env.getCurrentConfig();

        // first create map of tags based on the providers priority
        Map<String, String> newCommonTagValueMap = new HashMap<>();
        providers.forEach(provider -> provider.getTags(configuration).forEach(newCommonTagValueMap::putIfAbsent));

        // then create key/value tags pairs for resolved map
        List<String> newCommonTagKeys = new ArrayList<>();
        BaggageBuilder baggageBuilder = Baggage.builder();
        newCommonTagValueMap.forEach((k, v) -> {
            newCommonTagKeys.add(k);
            baggageBuilder.put(k, AttributesUtils.createAttributeValue(k, v));
        });
        commonTagKeys = newCommonTagKeys;
        commonTagValueMap = newCommonTagValueMap;
        commonBaggage = baggageBuilder.build();
    }

    /**
     * Converts the {@link #commonBaggage} to {@link Attributes}
     *
     * @return
     */
    public Attributes getCommonAttributes() {
        return AttributesUtils.fromBaggage(commonBaggage);
    }

}
