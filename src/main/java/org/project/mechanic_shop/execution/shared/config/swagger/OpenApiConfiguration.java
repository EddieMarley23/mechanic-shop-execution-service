package org.project.mechanic_shop.execution.shared.config.swagger;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
	info = @Info(
		title = "Mechanic Shop — Execution Service",
		version = "v1",
		description = """
			Execution queue of the mechanic shop: diagnosis and repair progress of each service order.

			Work orders are queued by Saga commands sent by the OS Service; this API is where the
			mechanics pick them up and report progress.

			Authenticate with the JWT issued by the OS Service (`POST /api/auth/login`).
			"""
	),
	security = { @SecurityRequirement(name = "bearerAuth") }
)
@SecurityScheme(
	name = "bearerAuth",
	type = SecuritySchemeType.HTTP,
	scheme = "bearer",
	bearerFormat = "JWT",
	in = SecuritySchemeIn.HEADER
)
public class OpenApiConfiguration {}
