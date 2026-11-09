package org.project.mechanic_shop.execution.shared.config.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(securedEnabled = true, jsr250Enabled = true)
@RequiredArgsConstructor
public class SecurityConfigurations {

	private final JwtAuthenticationFilter jwtAuthenticationFilter;

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) {
		return http
			// Stateless API authenticated by a Bearer token in the Authorization header: there are no
			// session cookies for a browser to send automatically, so there is nothing for CSRF to forge.
			.csrf(AbstractHttpConfigurer::disable) // NOSONAR java:S4502 - see the comment above
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(authorize ->
				authorize
					.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info")
					.permitAll()
					.requestMatchers(
						"/api/executions/v3/api-docs/**",
						"/api/executions/swagger-ui.html",
						"/api/executions/swagger-ui/**"
					)
					.permitAll()
					.anyRequest()
					.authenticated()
			)
			.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
			.build();
	}

	/**
	 * This service has no local users: identity comes only from the JWT. Declared so Spring Boot
	 * does not create its default in-memory user with a generated password.
	 */
	@Bean
	public UserDetailsService userDetailsService() {
		// Empty store: every lookup fails inside Spring Security, with no user-specific message.
		return new InMemoryUserDetailsManager();
	}
}
