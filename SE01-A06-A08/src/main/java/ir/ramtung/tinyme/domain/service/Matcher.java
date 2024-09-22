package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.repository.SecurityRepository;

import org.apache.activemq.artemis.api.core.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.event.OpeningPriceEvent;
import ir.ramtung.tinyme.messaging.request.MatchingState;

import java.util.LinkedList;
import java.util.ListIterator;
import java.lang.Math;
import java.time.LocalDateTime;

@Service
public class Matcher {
    @Autowired
    EventPublisher eventPublisher;
    @Autowired
    SecurityRepository securityRepository;

    public MatchResult match(Order newOrder) {
        OrderBook orderBook = newOrder.getSecurity().getOrderBook();
        LinkedList<Trade> trades = new LinkedList<>();

        while (orderBook.hasOrderOfType(newOrder.getSide().opposite()) && newOrder.getQuantity() > 0) {
            Order matchingOrder = orderBook.matchWithFirst(newOrder);
            if (matchingOrder == null)
                break;

            MatchResult matchResult =  makeTrade(matchingOrder.getPrice(), newOrder, matchingOrder, trades);
            if(matchResult.outcome() != MatchingOutcome.TRADEABLE_ORDER)
                return matchResult;

            changeQuantityAfterMatch(orderBook, newOrder, matchingOrder,MatchingState.CONTINUOUS);
        }
        MatchResult minExecQuanity = handleMinmumExecutionQuantity(trades, newOrder);
        return minExecQuanity;
    }

    private MatchResult checkMinimumExecutionQuantity(LinkedList<Trade> trades,Order newOrder){
        int totalTradesQuantity = 0;
        for (Trade trade : trades) {
            totalTradesQuantity += trade.getQuantity();
        } 
        if(totalTradesQuantity >= newOrder.getMinimumExecutionQuantity())
            return MatchResult.executed(newOrder, trades);
         else {
             rollbackTrades(newOrder, trades);
             return MatchResult.notSatisfiedMinExecQty();
        }
    }

    private MatchResult handleMinmumExecutionQuantity(LinkedList<Trade> trades,Order newOrder){
        MatchResult minExecQuanity = checkMinimumExecutionQuantity(trades, newOrder);
        if (minExecQuanity == MatchResult.notSatisfiedMinExecQty()){
            newOrder.getSecurity().getOrderBook().removeByOrderId(newOrder.getSide(), newOrder.getOrderId());
        }
        else{
            newOrder.setMinimumExecutionQuantity(0);
        }
        return minExecQuanity;
    }

    private void rollbackTrades(Order newOrder, LinkedList<Trade> trades) {
        if(newOrder.getSide() == Side.SELL)
            newOrder.getSecurity().getOrderBook().removeByOrderId(Side.SELL ,newOrder.getOrderId());
        else {
            newOrder.getBroker().increaseCreditBy(trades.stream().mapToLong(Trade::getTradedValue).sum());
            trades.forEach(trade -> trade.getSell().getBroker().decreaseCreditBy(trade.getTradedValue()));

            ListIterator<Trade> it = trades.listIterator(trades.size());
            while (it.hasPrevious()) {
                newOrder.getSecurity().getOrderBook().restoreSellOrder(it.previous().getSell());
            }
        }
    }

    public MatchResult execute(Order order,MatchingState matchingState,Security security) {
        if(matchingState == MatchingState.CONTINUOUS)
            return continousExecute(order);
        else{
            security.getOrderBook().enqueue(order);
            return auctionExecute(security);
        }
    }

    public MatchResult execute(Security security) {
            return auctionExecute(security);
    }

    public MatchResult continousExecute(Order order){
        MatchResult result = match(order);
        if (result.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT || result.outcome() == MatchingOutcome.MINIMUM_EXECUTION_QUANTITY_NOT_SATISFIED)
            return result;

        if (result.remainder().getQuantity() > 0) {
            if (order.getSide() == Side.BUY) {
                if (!order.getBroker().hasEnoughCredit((long)order.getPrice() * order.getQuantity())) {
                    rollbackTrades(order, result.trades());
                    return MatchResult.notEnoughCredit();
                }
                order.getBroker().decreaseCreditBy((long)order.getPrice() * order.getQuantity());
            }
            order.getSecurity().getOrderBook().enqueue(result.remainder());
        }
        updateShareholderPosition(result);
        return result;
    }

    private static void updateShareholderPosition(MatchResult result) {
        if (!result.trades().isEmpty()) {
            for (Trade trade : result.trades()) {
                trade.getBuy().getShareholder().incPosition(trade.getSecurity(), trade.getQuantity());
                trade.getSell().getShareholder().decPosition(trade.getSecurity(), trade.getQuantity());
            }
        }
    }

    private boolean validateOpeningPriceForOrder(int openingPrice,Order order){
        if(order.getSide().equals(Side.BUY))
            return order.getPrice() >= openingPrice;
        else 
            return order.getPrice() <= openingPrice;
    }

    private Order selectOrderForAuction(int openingPrice,OrderBook orderBook){
        if(orderBook.getBuyQueue().size() ==0)
            return null;
        Order newOrder = orderBook.getBuyQueue().getFirst();
        if(newOrder.equals(null) || !validateOpeningPriceForOrder(openingPrice, newOrder))
            return null;
        else return newOrder;
    }

    private LinkedList<Order> getQueueAuction(int openingPrice, OrderBook orderBook,Side side){
        LinkedList<Order> queue = new LinkedList<>();
        LinkedList<Order> allQueue = side==Side.BUY ?  orderBook.getBuyQueue():orderBook.getSellQueue();
        for(Order order :  allQueue){
            if(side==Side.BUY && order.getPrice()>=openingPrice)
                queue.add(order);
            else if(side==Side.SELL && order.getPrice()<=openingPrice)
                queue.add(order);
        }
        return queue;
    }

    public MatchResult auctionMatch(int openingPrice, OrderBook orderBook, Order newOrder) {
        LinkedList<Trade> trades = new LinkedList<>();
        while (orderBook.hasOrderOfType(newOrder.getSide().opposite()) && newOrder.getQuantity() > 0) {
            Order matchingOrder = orderBook.matchWithFirst(newOrder);
            if (matchingOrder == null)
                break;

            MatchResult matchResult =  makeTrade(openingPrice, newOrder, matchingOrder, trades);
            if(matchResult.outcome() != MatchingOutcome.TRADEABLE_ORDER)
                return matchResult;
            changeQuantityAfterMatch(orderBook, newOrder, matchingOrder,MatchingState.AUCTION);
        }
        return MatchResult.executed(newOrder, trades);
    }

    private MatchResult makeTrade(int price, Order newOrder, Order matchingOrder, LinkedList<Trade> trades) {
        Trade trade = new Trade(newOrder.getSecurity(), price, Math.min(newOrder.getQuantity(), matchingOrder.getQuantity()), newOrder, matchingOrder);
        if (newOrder.getSide() == Side.BUY) {
            if (trade.buyerHasEnoughCredit())
                trade.decreaseBuyersCredit();
            else {
                rollbackTrades(newOrder, trades);
                return MatchResult.notEnoughCredit();  // TODO there should be something instead of this return
            }
        }
        trade.increaseSellersCredit();
        trades.add(trade);
        return MatchResult.orderIsTradeable();
    }

    private static void changeQuantityAfterMatch(OrderBook orderBook, Order newOrder, Order matchingOrder,MatchingState matchingState) {
        if (newOrder.getQuantity() >= matchingOrder.getQuantity()) {
            newOrder.decreaseQuantity(matchingOrder.getQuantity());
            orderBook.removeFirst(matchingOrder.getSide());
            if (matchingOrder instanceof IcebergOrder icebergOrder) {
                icebergOrder.decreaseQuantity(matchingOrder.getQuantity());
                icebergOrder.replenish();
                if (icebergOrder.getQuantity() > 0){
                    if(matchingState == MatchingState.AUCTION)
                        orderBook.pushAtEnd(icebergOrder);
                    else
                        orderBook.enqueue(icebergOrder);
                }
            }
        } else {
            matchingOrder.decreaseQuantity(newOrder.getQuantity());
            if(newOrder instanceof IcebergOrder icebergOrder){
                newOrder.decreaseQuantity(newOrder.getQuantity());
                icebergOrder.replenish();
            }else{
                newOrder.makeQuantityZero();
            }

        }
    }
    
    
    private void applyOrderDeletedOnSecurity(OrderBook tempOrderBook, int openingPrice, OrderBook orderBook, LinkedList<Order> deletableOrders, Side side) {
        LinkedList<Order> orderQueue;

        orderQueue = side==Side.BUY ?  orderBook.getBuyQueue() : orderBook.getSellQueue();

        for(Order order: orderQueue){
            if(validateOpeningPriceForOrder(openingPrice, order)){
                if(tempOrderBook.findByOrderId(side, order.getOrderId()) == null){  // so order is deleted
                    deletableOrders.add(order);
                } else{
                    orderBook.update(order);
                }
            }
        }
    }

    private static void applySellRemainderOnSecurity(OrderBook tempOrderBook, OrderBook orderBook) {
            if(tempOrderBook.getSellQueue().size() == 0)
                return;
            Order remainderSellOrder = tempOrderBook.getSellQueue().getFirst();
            orderBook.removeByOrderId(Side.SELL, remainderSellOrder.getOrderId());
            orderBook.update(remainderSellOrder);
        }

    private void applyAuctionTradesOnSecurity(Security security,OrderBook tempOrderBook,int openingPrice){ 
        LinkedList<Order> deletableOrders = new LinkedList<>();
        OrderBook orderBook = security.getOrderBook();
        applyOrderDeletedOnSecurity(tempOrderBook, openingPrice, orderBook, deletableOrders,Side.BUY);
        applyOrderDeletedOnSecurity(tempOrderBook, openingPrice, orderBook, deletableOrders,Side.SELL);

        for(Order order : deletableOrders)
            orderBook.removeByOrderId(order.getSide(), order.getOrderId());

        // applySellRemainderOnSecurity(tempOrderBook, orderBook);

    }

    private MatchResult auctionExecuteOrderAndCheck(Security security, int openingPrice, OrderBook orderBook, Order newOrder) { // TODO what the hell is this name
        MatchResult result = auctionMatch(openingPrice, orderBook, newOrder);
        
        if (result.remainder().getQuantity() > 0) {
            orderBook.update(result.remainder()); //TODO change of olaviat
            if(result.remainder().equals(newOrder))
                return null; // No matching happedn so we are at the end
           
            return result;
        }
        else{
            auctionCheckIceBerg(orderBook, newOrder);
            if(orderBook.findByOrderId(newOrder.getSide(), newOrder.getOrderId()).getQuantity() == 0)
               orderBook.removeByOrderId(newOrder.getSide(), newOrder.getOrderId());
        }
        updateShareholderPosition(result);
        return result;
    }

    private static void auctionCheckIceBerg(OrderBook orderBook, Order newOrder) {
        if (newOrder instanceof IcebergOrder icebergOrder) {
            icebergOrder.decreaseQuantity(newOrder.getQuantity());
            icebergOrder.replenish();
            if (icebergOrder.getQuantity() > 0){
                orderBook.pushAtEnd(icebergOrder);
            }
        }
    }

    private OrderBook getTempOrderBookForAuction(Security security, int openingPrice) {
        LinkedList<Order> buyQueue = getQueueAuction(openingPrice, security.getOrderBook(),Side.BUY);
        LinkedList<Order> sellQueue = getQueueAuction(openingPrice, security.getOrderBook(),Side.SELL);
        OrderBook orderBook = new OrderBook(buyQueue, sellQueue);
        return orderBook;
    }

    public MatchResult auctionExecute(Security security){
        Pair<Integer, Integer> minPriceAndQuantity = security.calculateAuctionPriceAndQuantity(); 
        int openingPrice = minPriceAndQuantity.getA();
        eventPublisher.publish(new OpeningPriceEvent(LocalDateTime.now().withNano(0).withSecond(0),security.getIsin(),openingPrice, minPriceAndQuantity.getB()));
        LinkedList<Trade> allTrades = new LinkedList<>();
        OrderBook orderBook = getTempOrderBookForAuction(security, openingPrice);

        Order newOrder = selectOrderForAuction(openingPrice,orderBook);
        while(newOrder != null) {
            MatchResult result = auctionExecuteOrderAndCheck(security, openingPrice, orderBook, newOrder);
            if (result == null) break;
            allTrades.addAll(result.trades());
            newOrder = selectOrderForAuction(openingPrice, orderBook);
        }
        if(allTrades.size() !=0){
            applyAuctionTradesOnSecurity(security,orderBook,openingPrice);
        }
        security.setLastPrice(openingPrice);
        return MatchResult.auctionExecuted(newOrder, allTrades); // TODO change this to new MatcResult or return list of match results
    }

}
