package ir.ramtung.tinyme.domain.validator;

import ir.ramtung.tinyme.domain.entity.IcebergOrder;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.domain.entity.Order;
import ir.ramtung.tinyme.domain.entity.Side;

public class OrderValidator {
    public static boolean CheckOrderIdIsValid(long deleteOrderRq) {
        return deleteOrderRq <= 0;
    }
    public static boolean checkPeakSizeIsValid(EnterOrderRq enterOrderRq) {
        return enterOrderRq.getPeakSize() < 0 || enterOrderRq.getPeakSize() >= enterOrderRq.getQuantity();
    }
    public static boolean checkOrderIsNull(Order order) {
        return order == null;
    }

    public static boolean checkOrderSideIsBuy(Side side) {
        return side == Side.BUY;
    }

    public static boolean checkIceBergOrderPeakSizeIsValid(Order order, EnterOrderRq updateOrderRq) {
        return (order instanceof IcebergOrder) && updateOrderRq.getPeakSize() == 0;
    }
}
