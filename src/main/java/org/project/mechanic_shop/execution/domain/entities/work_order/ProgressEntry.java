package org.project.mechanic_shop.execution.domain.entities.work_order;

import java.time.Instant;
import org.project.mechanic_shop.execution.domain.enums.WorkOrderStatusEnum;

/** One line of the work order's history: which status was reached, when, by whom and why. */
public record ProgressEntry(WorkOrderStatusEnum status, Instant at, String by, String note) {}
