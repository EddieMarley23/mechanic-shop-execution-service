package org.project.mechanic_shop.execution.domain.entities.work_order;

import java.util.UUID;
import org.project.mechanic_shop.execution.domain.enums.WorkItemTypeEnum;

/**
 * A service or part the mechanic judged necessary. Only the reference and the quantity are kept:
 * names and prices belong to the catalog and the stock of the OS Service.
 */
public record WorkItem(WorkItemTypeEnum type, UUID referenceId, Integer quantity) {}
