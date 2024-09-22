package ir.ramtung.tinyme.domain.validator;

import ir.ramtung.tinyme.domain.entity.IcebergOrder;
import ir.ramtung.tinyme.domain.entity.Order;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.repository.EntityRepository;

public class SecurityValidator {
    public static boolean CheckSecurityIsAvailable(String SecurityIsin, EntityRepository entityRepository) {
        return entityRepository.findSecurityByIsin(SecurityIsin) == null;
    }

    public static boolean checkUpdateLosesPriority(EnterOrderRq updateOrderRq, Order order) {
        return order.isQuantityIncreased(updateOrderRq.getQuantity())
                || updateOrderRq.getPrice() != order.getPrice()
                || ((order instanceof IcebergOrder icebergOrder)
                && (icebergOrder.getPeakSize() < updateOrderRq.getPeakSize()));
    }
}
