package ir.ramtung.tinyme.domain.validator;

import ir.ramtung.tinyme.domain.entity.Side;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.repository.EntityRepository;

public class BrokerValidator {
    public static boolean checkBrokerIsAvailable(EnterOrderRq enterOrderRq, EntityRepository en) {
        return en.findBrokerById(enterOrderRq.getBrokerId()) == null;
    }

    public static boolean CheckBuyerHasEnoughCredit(EnterOrderRq enterOrderRq, EntityRepository en) {
        return enterOrderRq.getSide() == Side.BUY && (long) enterOrderRq.getQuantity() * enterOrderRq.getPrice() > en.findBrokerById(enterOrderRq.getBrokerId()).getCredit();
    }
}
