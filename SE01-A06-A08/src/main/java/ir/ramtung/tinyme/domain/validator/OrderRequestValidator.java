package ir.ramtung.tinyme.domain.validator;

import ir.ramtung.tinyme.domain.entity.Security;
import ir.ramtung.tinyme.domain.entity.Side;
import ir.ramtung.tinyme.domain.entity.StopLimitQueue;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import ir.ramtung.tinyme.messaging.request.OrderEntryType;
import ir.ramtung.tinyme.repository.EntityRepository;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import static ir.ramtung.tinyme.messaging.Message.QUANTITY_NOT_MULTIPLE_OF_LOT_SIZE;

public class OrderRequestValidator {

    public static boolean checkNewOrderIsNotStopLimit(EnterOrderRq enterOrderRq, boolean isStopLimit) {
        return enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER && !isStopLimit;
    }

    public static boolean CheckRequestIsNewOrder(EnterOrderRq enterOrderRq) {
        return enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER;
    }

    public static boolean CheckOrderQuantityIsMultipleOfLotSize(EnterOrderRq enterOrderRq, Security security) {
        return enterOrderRq.getQuantity() % security.getLotSize() != 0;
    }

    public static boolean CheckOrderPriceIsMultipleOfTickSize(EnterOrderRq enterOrderRq, Security security) {
        return enterOrderRq.getPrice() % security.getTickSize() != 0;
    }

    public static void validateDeleteOrderRq(DeleteOrderRq deleteOrderRq, EntityRepository entityRepository) throws InvalidRequestException {
        List<String> errors = new LinkedList<>();
        if (OrderValidator.CheckOrderIdIsValid(deleteOrderRq.getOrderId()))
            errors.add(Message.INVALID_ORDER_ID);
        if (SecurityValidator.CheckSecurityIsAvailable(deleteOrderRq.getSecurityIsin() , entityRepository))
            errors.add(Message.UNKNOWN_SECURITY_ISIN);
        if (!errors.isEmpty())
            throw new InvalidRequestException(errors);
    }


    public static List<String> validateOrderRequestValues(EnterOrderRq enterOrderRq , EntityRepository entityRepository) {
        List<String> errors = new ArrayList<>();
        if (OrderValidator.CheckOrderIdIsValid(enterOrderRq.getOrderId()))
            errors.add(Message.INVALID_ORDER_ID);
        if (OrderValidator.CheckOrderIdIsValid(enterOrderRq.getQuantity()))
            errors.add(Message.ORDER_QUANTITY_NOT_POSITIVE);
        if(enterOrderRq.getSide()== Side.BUY && (long) enterOrderRq.getQuantity() * enterOrderRq.getPrice() > entityRepository.findBrokerById(enterOrderRq.getBrokerId()).getCredit())
            errors.add(Message.BUYER_HAS_NOT_ENOUGH_CREDIT);
        if (OrderValidator.CheckOrderIdIsValid(enterOrderRq.getPrice()))
            errors.add(Message.ORDER_PRICE_NOT_POSITIVE);
        if (OrderValidator.checkPeakSizeIsValid(enterOrderRq))
            errors.add(Message.INVALID_PEAK_SIZE);
        if (BrokerValidator.checkBrokerIsAvailable(enterOrderRq , entityRepository))
            errors.add(Message.UNKNOWN_BROKER_ID);
        if (ShareholderValidator.checkShareHolderIsAvailable(enterOrderRq , entityRepository))
            errors.add(Message.UNKNOWN_SHAREHOLDER_ID);
        return errors;
    }

    public static List<String> validateTickAndLotValues(EnterOrderRq enterOrderRq, Security security) {
        List<String> errors = new ArrayList<>();
        if (OrderRequestValidator.CheckOrderQuantityIsMultipleOfLotSize(enterOrderRq , security))
            errors.add(QUANTITY_NOT_MULTIPLE_OF_LOT_SIZE);
        if (OrderRequestValidator.CheckOrderPriceIsMultipleOfTickSize(enterOrderRq , security))
            errors.add(Message.PRICE_NOT_MULTIPLE_OF_TICK_SIZE);
        return errors;
    }

    public static boolean checkOrderIsIceBerg(EnterOrderRq enterOrderRq) {
        return enterOrderRq.getPeakSize() != 0;
    }

    public static boolean checkOrderIsStopLimit(EnterOrderRq enterOrderRq) {
        return enterOrderRq.getStopPrice() != 0;
    }

    public static void validateEnterOrderRq(EnterOrderRq enterOrderRq ,EntityRepository entityRepository) throws InvalidRequestException {
        List<String> errors = validateOrderRequestValues(enterOrderRq , entityRepository);
        errors.addAll(validateSecurityOfOrderRq(enterOrderRq,entityRepository));
        if (!errors.isEmpty())
            throw new InvalidRequestException(errors);
    }
    public static List<String> validateSecurityOfOrderRq(EnterOrderRq enterOrderRq, EntityRepository entityRepository) {
        List<String> errors = new ArrayList<>();
        Security security = entityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
        if (SecurityValidator.CheckSecurityIsAvailable(enterOrderRq.getSecurityIsin(), entityRepository))
            errors.add(Message.UNKNOWN_SECURITY_ISIN);
        else
            errors.addAll(OrderRequestValidator.validateTickAndLotValues(enterOrderRq, security));
        return errors;
    }

    public static boolean IsOrderStopLimitInAuctionState(EnterOrderRq updateOrderRq , MatchingState matchingState , StopLimitQueue stopLimitOrderList) {
        return matchingState == MatchingState.AUCTION && stopLimitOrderList.hasOrderWithId(updateOrderRq.getOrderId());
    }

    public static boolean validateBuyOrderStopPriceUpdate(EnterOrderRq updateOrderRq) {
        return (updateOrderRq.getStopPrice() >= updateOrderRq.getPrice()) || updateOrderRq.getStopPrice() <= 0;
    }

    public static boolean validateSellOrderStopPriceUpdate(EnterOrderRq updateOrderRq) {
        return (updateOrderRq.getStopPrice() <= updateOrderRq.getPrice()) || updateOrderRq.getStopPrice() <= 0;
    }
}
