package org.project.mechanic_shop.execution.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Why the repair could not be carried out")
public record RepairFailureDto(
	@Schema(description = "Reason reported by the mechanic", example = "Bloco do motor trincado: reparo inviável")
	@NotBlank String reason
) {}
