package org.project.mechanic_shop.execution.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

@Schema(description = "Mechanic's diagnosis and the services and parts needed for the repair")
public record DiagnosisDto(
	@Schema(description = "Technical description of the diagnosed problem", example = "Pastilhas dianteiras desgastadas, troca necessária")
	@NotBlank String mechanicDiagnosis,
	@Schema(description = "List of parts/consumables to be used")
	@Valid List<Part> parts,
	@Schema(description = "List of labour items to be performed")
	@Valid List<Labor> labors
) {
	@Schema(description = "A labour item from the OS Service catalog")
	public record Labor(
		@Schema(description = "External ID of the mechanic service from the catalog", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
		@NotNull UUID mechanicServiceExternalId,
		@Schema(description = "Number of times this service will be performed", example = "1")
		@NotNull @Min(1) Integer quantity
	) {}

	@Schema(description = "A part or consumable from the OS Service stock")
	public record Part(
		@Schema(description = "External ID of the stock item", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
		@NotNull UUID partExternalId,
		@Schema(description = "Quantity needed", example = "2")
		@NotNull @Min(1) Integer quantity
	) {}
}
