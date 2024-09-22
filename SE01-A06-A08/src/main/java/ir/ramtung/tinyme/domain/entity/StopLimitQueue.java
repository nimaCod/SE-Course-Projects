package ir.ramtung.tinyme.domain.entity;
import java.util.LinkedList;

import lombok.Getter;

@Getter
public class StopLimitQueue {

    public StopLimitQueue()
    {
        this.orderQueue = new LinkedList<>();
    }
    private final LinkedList<StopLimitOrder> orderQueue;
    public StopLimitOrder getInactiveOrder(long id) {
        for(var order : orderQueue)
            if(order.getOrderId() == id)
                return order;
        return null;
    }

    public boolean removeOrder(long id) {
        var it = orderQueue.listIterator();
        while (it.hasNext()) {
            if (it.next().getOrderId() == id) {
                it.remove();
                return true;
            }
        }
        return false;
    }
    public LinkedList<Order> getLatestAwokedOrders(int lastTradedPrice){
        LinkedList<Order> newTrades = new LinkedList<>() ;
        if(orderQueue.isEmpty())
            return newTrades;
        for(StopLimitOrder limitOrder: orderQueue){
            if(isOrderActivated(lastTradedPrice, limitOrder)){
                newTrades.add(limitOrder.getSimpleOrder());
                orderQueue.remove(limitOrder);
            }
        }
        return newTrades;
    }
    private boolean isOrderActivated(int lastTradedPrice, StopLimitOrder limitOrder){
        if(limitOrder.getSide() == Side.BUY && limitOrder.getStopPrice() <= lastTradedPrice)
            return true;
        else return limitOrder.getSide() == Side.SELL && limitOrder.getStopPrice() >= lastTradedPrice;
    }

    public void addNewOrder(StopLimitOrder order)
    {
        orderQueue.add(order);
    }

    public boolean hasOrderWithId(long orderId){
        for(var order : orderQueue)
            if(order.getOrderId() == orderId)
                return true;
        return false;
    }

}
