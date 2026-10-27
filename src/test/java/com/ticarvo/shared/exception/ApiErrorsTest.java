package com.ticarvo.shared.exception;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import static org.junit.jupiter.api.Assertions.*;

/** ARCH-M3-03: the version-conflict code follows the failing entity, whatever the URL looks like. */
class ApiErrorsTest {
    @Test void versionCodeComesFromTheEntity() {
        assertEquals("PRODUCT_VERSION_CONFLICT",ApiErrors.versionCode(new ObjectOptimisticLockingFailureException("com.ticarvo.catalog.entity.Product",1)));
        assertEquals("RELATIONSHIP_VERSION_CONFLICT",ApiErrors.versionCode(new ObjectOptimisticLockingFailureException("com.ticarvo.trade.entity.TradeRelationship",1)));
        assertEquals("ORDER_VERSION_CONFLICT",ApiErrors.versionCode(new ObjectOptimisticLockingFailureException("com.ticarvo.order.entity.PurchaseOrder",1)));
        assertEquals("VERSION_CONFLICT",ApiErrors.versionCode(new ObjectOptimisticLockingFailureException("com.ticarvo.admin.entity.Complaint",1)));
        assertEquals("VERSION_CONFLICT",ApiErrors.versionCode(new OptimisticLockingFailureException("no entity")));
    }
}
