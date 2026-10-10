package org.project.mechanic_shop.execution.domain.dto;

public record ApiResponse(int status, String message, Object data) {}
