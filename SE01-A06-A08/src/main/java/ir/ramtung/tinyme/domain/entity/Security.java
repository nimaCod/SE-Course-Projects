package ir.ramtung.tinyme.domain.entity;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

import ir.ramtung.tinyme.domain.validator.OrderRequestValidator;
import ir.ramtung.tinyme.domain.validator.OrderValidator;
import ir.ramtung.tinyme.domain.validator.SecurityValidator;
import org.apache.activemq.artemis.api.core.Pair;

import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class Security {
    @Builder.Default
    private MatchingState matchingState = MatchingState.CONTINUOUS;
    @Builder.Default
    private int lastPrice = 100_000_000;
    private String isin;
    @Builder.Default
    private int tickSize = 1;
    @Builder.Default
    private int lotSize = 1;
    @Builder.Default
    private OrderBook orderBook = new OrderBook();
    @Builder.Default
    private StopLimitQueue stopLimitOrderList = new StopLimitQueue();

    public LinkedList<Order> getLatestAwokedStopLimitOrders(int lastPrice)
    {
        return stopLimitOrderList.getLatestAwokedOrders(lastPrice);
    }

    public MatchResult newOrder(EnterOrderRq enterOrderRq, EntityObj entityObj, Matcher matcher) {
        if (checkShareHolderEnoughPosition(enterOrderRq, entityObj.shareholder))
            return MatchResult.notEnoughPositions();
        Order order;
        if (OrderRequestValidator.checkOrderIsIceBerg(enterOrderRq))
            order = new IcebergOrder(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), entityObj.broker, entityObj.shareholder,
                    enterOrderRq.getEntryTime(), enterOrderRq.getPeakSize(),
                    enterOrderRq.getMinimumExecutionQuantity());
        else {
            if (OrderRequestValidator.checkOrderIsStopLimit(enterOrderRq)) {
                stopLimitOrderList
                        .addNewOrder(new StopLimitOrder(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                                enterOrderRq.getQuantity(), enterOrderRq.getPrice(), enterOrderRq.getStopPrice(),
                                entityObj.broker, entityObj.shareholder, enterOrderRq.getEntryTime(), OrderStatus.NEW));
                return MatchResult.inactiveStopLimit();
            } else {
                order = new Order(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                        enterOrderRq.getQuantity(), enterOrderRq.getPrice(), entityObj.broker, entityObj.shareholder,
                        enterOrderRq.getEntryTime(), OrderStatus.NEW, enterOrderRq.getMinimumExecutionQuantity());
            }
        }

        return matcher.execute(order, matchingState, this);
    }

    private boolean checkShareHolderEnoughPosition(EnterOrderRq enterOrderRq, Shareholder shareholder) {
        return enterOrderRq.getSide() == Side.SELL &&
                !shareholder.hasEnoughPositionsOn(this,
                        orderBook.totalSellQuantityByShareholder(shareholder) + enterOrderRq.getQuantity());
    }

    public void deleteOrder(DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
        if (!stopLimitOrderList.removeOrder(deleteOrderRq.getOrderId())) {
            Order order = orderBook.findByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
            if (OrderValidator.checkOrderIsNull(order))
                throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
            if (!OrderValidator.checkOrderSideIsBuy(order.getSide()))
                order.getBroker().increaseCreditBy(order.getValue());
            orderBook.removeByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        }
    }

    public StopLimitOrder findInactiveStopLimitOrder(long id) {
        return stopLimitOrderList.getInactiveOrder(id);
    }

    public void validateStopLimitForUpdate(EnterOrderRq updateOrderRq) throws InvalidRequestException {
        if (stopLimitOrderHasInvalidStopPrice(updateOrderRq))
            throw new InvalidRequestException(Message.INVALID_STOP_PRICE);

        if (OrderRequestValidator.checkOrderIsIceBerg(updateOrderRq))
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER);
        if (!OrderRequestValidator.checkOrderIsStopLimit(updateOrderRq))
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_MINIMUM_EXECUTION_QUANTITY_FOR_A_STOP_LIMIT_ORDER);
    }

    private static boolean stopLimitOrderHasInvalidStopPrice(EnterOrderRq updateOrderRq) {
        if (OrderValidator.checkOrderSideIsBuy(updateOrderRq.getSide()))
            return OrderRequestValidator.validateBuyOrderStopPriceUpdate(updateOrderRq);
        else
            return OrderRequestValidator.validateSellOrderStopPriceUpdate(updateOrderRq);
    }

    private boolean updateInactiveStopLimitOrder(EnterOrderRq updateOrderRq) throws InvalidRequestException {
        StopLimitOrder stopLimitOrder = stopLimitOrderList.getInactiveOrder(updateOrderRq.getOrderId());
        if (OrderValidator.checkOrderIsNull(stopLimitOrder))
            return false;
        validateStopLimitForUpdate(updateOrderRq);
        stopLimitOrder.updateFromRequest(updateOrderRq);
        return true;
    }

    public void checkOrderValidity(Order order, EnterOrderRq updateOrderRq) throws InvalidRequestException {
        if (OrderValidator.checkOrderIsNull(order))
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if (OrderValidator.checkIceBergOrderPeakSizeIsValid(order, updateOrderRq))
            throw new InvalidRequestException(Message.INVALID_PEAK_SIZE);
        if (OrderRequestValidator.checkOrderIsStopLimit(updateOrderRq))
            throw new InvalidRequestException(Message.INVALID_STOP_PRICE);
        if (!(order instanceof IcebergOrder) && OrderRequestValidator.checkOrderIsIceBerg(updateOrderRq))
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER);
    }

    public void enqueueOrderIfNotExecuted(MatchResult matchResult, Order originalOrder, EnterOrderRq updateOrderRq) {
        if (matchResult.outcome() != MatchingOutcome.EXECUTED) {
            orderBook.enqueue(originalOrder);
            if (OrderValidator.checkOrderSideIsBuy(updateOrderRq.getSide()))
                originalOrder.getBroker().decreaseCreditBy(originalOrder.getValue());
        }
    }

    private MatchResult updateOrderIfPossibleThenMatch(EnterOrderRq updateOrderRq, Order order, Matcher matcher) {
        boolean losesPriority = SecurityValidator.checkUpdateLosesPriority(updateOrderRq, order);

        Order originalOrder = order.snapshot();

        order.updateFromRequest(updateOrderRq);
        if (!losesPriority) {
            if (OrderValidator.checkOrderSideIsBuy(updateOrderRq.getSide()))
                order.getBroker().decreaseCreditBy(order.getValue());
            return MatchResult.executed(null, List.of());
        }
        orderBook.removeByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());

        MatchResult matchResult = matcher.execute(order, matchingState, this);
        enqueueOrderIfNotExecuted(matchResult, originalOrder, updateOrderRq);
        return matchResult;
    }

    public MatchResult updateOrder(EnterOrderRq updateOrderRq, Matcher matcher) throws InvalidRequestException {
        Order order;
        if (OrderRequestValidator.IsOrderStopLimitInAuctionState(updateOrderRq , matchingState , stopLimitOrderList))
            throw new InvalidRequestException(Message.UPDATE_STOP_LIMIT_ORDER_REJECTED_ON_AUCTION_MODE);
        if (updateInactiveStopLimitOrder(updateOrderRq))
            return MatchResult.stopLimitOrderUpdatedSuccessfully();
        else
            order = orderBook.findByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());

        checkOrderValidity(order, updateOrderRq);

        if (updateOrderRq.getSide() == Side.SELL &&
                !order.getShareholder().hasEnoughPositionsOn(this, calcPositionForUpdateOrderRq(updateOrderRq, order)))
            return MatchResult.notEnoughPositions();

        if (OrderValidator.checkOrderSideIsBuy(updateOrderRq.getSide()))
            order.getBroker().increaseCreditBy(order.getValue());

        return updateOrderIfPossibleThenMatch(updateOrderRq, order, matcher);
    }

    private int calcPositionForUpdateOrderRq(EnterOrderRq updateOrderRq, Order order) {
        return orderBook.totalSellQuantityByShareholder(order.getShareholder()) - order.getQuantity()
                + updateOrderRq.getQuantity();
    }

    public void setMatchingState(MatchingState newState) {
        matchingState = newState;
    }

    public void setLastPrice(int lastPrice) {
        this.lastPrice = lastPrice;
    }

    public int getLastPrice() {
        return lastPrice;
    }

    private HashSet<Integer> getAllUniquePrices() {
        HashSet<Integer> allPrices = new HashSet<Integer>();
        getUniquePriceOfSide(orderBook.getSellQueue(), allPrices);
        getUniquePriceOfSide(orderBook.getBuyQueue(), allPrices);
        return allPrices;
    }

    private void getUniquePriceOfSide(LinkedList<Order> orderBook, HashSet<Integer> allPrices) {
        for (Order order : orderBook)
            allPrices.add(order.getPrice());
    }

    private boolean needToUpdateAuctionPriceAndQuantity(int currentPrice, int currentQuantity, int newPrice, int newQuantity) {
        if (newQuantity > currentQuantity)
            return true;
        else if (newQuantity == currentQuantity &&
                Math.abs(currentPrice - lastPrice) > Math.abs(newPrice - lastPrice)) {
            return true;
        } else if (newQuantity == currentQuantity &&
                Math.abs(currentPrice - lastPrice) == Math.abs(newPrice - lastPrice)) {
            return newPrice < currentPrice;
        }
        return false;
    }

    public Pair<Integer, Integer> calculateAuctionPriceAndQuantity() {
        HashSet<Integer> allPrices = getAllUniquePrices();

        int minPrice = 0;
        int minPriceQuantity = 0;
        for (var price : allPrices) {
            int thisPriceQuantity = calculateAuctionTradedQuantity(price);
            if (needToUpdateAuctionPriceAndQuantity(minPrice, minPriceQuantity, price, thisPriceQuantity)) {
                minPrice = price;
                minPriceQuantity = thisPriceQuantity;
            }
        }
        if (minPriceQuantity == 0)
            minPrice = 0;
        return new Pair<Integer, Integer>(minPrice, minPriceQuantity);
    }

    private int calculateAuctionTradedQuantity(int price) {
        int buyQuantity = getAllQuantityOfSide(price, 0, Side.BUY);
        int sellQuantity = getAllQuantityOfSide(price, 0, Side.SELL);
        return Math.min(sellQuantity, buyQuantity);
    }

    private boolean comparePriceOrder(int orderPrice, int destOrderPrice, Side orderSide) {
        if (!OrderValidator.checkOrderSideIsBuy(orderSide))
            return orderPrice <= destOrderPrice;
        else
            return orderPrice >= destOrderPrice;
    }

    private int getAllQuantityOfSide(int price, int buyQuantity, Side side) {
        for (Order order : side == Side.SELL ? orderBook.getSellQueue() : orderBook.getBuyQueue()) {
            if (comparePriceOrder(order.getPrice(), price, side)) {
                if (order instanceof IcebergOrder ice)
                    buyQuantity += ice.getWholeQuantity();
                else
                    buyQuantity += order.getQuantity();
            }
        }
        return buyQuantity;
    }

}