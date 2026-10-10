package org.project.mechanic_shop.execution.domain.dto;

import java.util.List;

public record ErrorResponse(int status, String message, List<ErrorField> errors) {
	public record ErrorField(String field, String message) {}

	public static ErrorResponse of(int status, String message) {
		return new ErrorResponse(status, message, List.of());
	}
}
