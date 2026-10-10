package org.project.mechanic_shop.execution.application.saga;

/**
 * Payload of {@link SagaMessageTypes#DIAGNOSIS_START}. Carries a copy of everything the shop floor
 * needs to know about the order, so this service never has to query the OS Service.
 */
public record StartDiagnosisPayload(
	String licensePlate,
	String brand,
	String model,
	Integer year,
	String customerName,
	String customerComplaint,
	Integer odometerReading,
	String mechanicEmail
) {}
