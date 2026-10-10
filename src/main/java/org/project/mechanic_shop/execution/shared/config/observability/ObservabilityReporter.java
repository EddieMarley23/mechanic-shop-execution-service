package org.project.mechanic_shop.execution.shared.config.observability;

import com.newrelic.api.agent.NewRelic;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Single entry point for reporting failures to New Relic. Reporting telemetry must never change
 * the outcome of the caller, so every method swallows its own exceptions.
 */
@Component
@Slf4j
public class ObservabilityReporter {

	/** One event per failure while handling a Saga message. Same event name in the three services. */
	public static final String SAGA_FAILURE = "MechanicShopSagaFailure";
	/** One event per outbound integration failure (broker, database, …). */
	public static final String INTEGRATION_FAILURE = "MechanicShopIntegrationFailure";

	private static final String SERVICE = "execution";

	public void recordSagaFailure(String messageType, UUID sagaId, UUID serviceOrderId, Throwable throwable) {
		Map<String, Object> attributes = baseAttributes(throwable);
		attributes.put("messageType", String.valueOf(messageType));
		attributes.put("sagaId", String.valueOf(sagaId));
		attributes.put("serviceOrderId", String.valueOf(serviceOrderId));

		record(SAGA_FAILURE, attributes, throwable);
	}

	public void recordIntegrationFailure(String integration, String operation, Throwable throwable) {
		Map<String, Object> attributes = baseAttributes(throwable);
		attributes.put("integration", integration);
		attributes.put("operation", operation);

		record(INTEGRATION_FAILURE, attributes, throwable);
	}

	private void record(String eventType, Map<String, Object> attributes, Throwable throwable) {
		try {
			NewRelic.getAgent().getInsights().recordCustomEvent(eventType, attributes);
			NewRelic.noticeError(throwable, attributes);
		} catch (Exception e) {
			log.warn("Failed to report '{}' telemetry: {}", eventType, e.getMessage());
		}
	}

	private Map<String, Object> baseAttributes(Throwable throwable) {
		Map<String, Object> attributes = new HashMap<>();
		attributes.put("service", SERVICE);
		attributes.put("errorClass", throwable.getClass().getName());
		attributes.put("errorMessage", String.valueOf(throwable.getMessage()));

		String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
		if (correlationId != null) attributes.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, correlationId);
		return attributes;
	}
}
