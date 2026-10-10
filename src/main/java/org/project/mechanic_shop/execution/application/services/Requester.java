package org.project.mechanic_shop.execution.application.services;

/**
 * Who is calling a use case. Built by the presentation layer from the JWT, so the application
 * layer does not depend on the security framework.
 */
public record Requester(String email, String role) {
	public static final String CUSTOMER_ROLE = "CUSTOMER";

	public boolean isCustomer() {
		return CUSTOMER_ROLE.equals(role);
	}
}
