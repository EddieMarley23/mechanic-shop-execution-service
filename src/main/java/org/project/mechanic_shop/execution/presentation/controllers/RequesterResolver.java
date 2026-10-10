package org.project.mechanic_shop.execution.presentation.controllers;

import org.project.mechanic_shop.execution.application.services.Requester;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

final class RequesterResolver {

	private static final String ROLE_PREFIX = "ROLE_";

	private RequesterResolver() {}

	static Requester from(Authentication authentication) {
		String role = authentication
			.getAuthorities()
			.stream()
			.map(GrantedAuthority::getAuthority)
			.filter(a -> a != null && a.startsWith(ROLE_PREFIX))
			.map(a -> a.substring(ROLE_PREFIX.length()))
			.findFirst()
			.orElse(null);

		return new Requester(authentication.getName(), role);
	}
}
