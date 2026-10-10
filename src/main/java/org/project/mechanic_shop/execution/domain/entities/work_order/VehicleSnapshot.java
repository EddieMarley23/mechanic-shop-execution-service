package org.project.mechanic_shop.execution.domain.entities.work_order;

/** Copy of the vehicle data received from the OS Service when the work order was queued. */
public record VehicleSnapshot(String licensePlate, String brand, String model, Integer year) {}
