package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;

@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class StopLimitOrder extends Order {

    private int stopPrice;

    public StopLimitOrder(long orderId, Security security, Side side, int quantity, int price, int stopPrice , Broker broker, Shareholder shareholder, LocalDateTime entryTime, OrderStatus status) {
        super(orderId, security, side, quantity, price, broker, shareholder, entryTime, status);
        this.stopPrice = stopPrice;
    }

    @Override
    public StopLimitOrder snapshot() {
        return new StopLimitOrder(orderId, security, side, quantity, price, stopPrice , broker, shareholder, entryTime ,OrderStatus.SNAPSHOT);
    }

    @Override
    public Order snapshotWithQuantity(int newQuantity) {
        return new StopLimitOrder(orderId, security, side, newQuantity, price, stopPrice , broker, shareholder, entryTime , OrderStatus.SNAPSHOT);
    }

    @Override
    public void decreaseQuantity(int amount) {
        if (status == OrderStatus.NEW) {
            super.decreaseQuantity(amount);
            return;
        }
        if (amount > quantity)
            throw new IllegalArgumentException();
        quantity -= amount;
    }

    public Order getSimpleOrder() {
        return  new Order(orderId, security, side, quantity, price, broker, shareholder, entryTime, status);
    }

    @Override
    public void updateFromRequest(EnterOrderRq updateOrderRq) {
        super.updateFromRequest(updateOrderRq);
        stopPrice = updateOrderRq.getStopPrice();
    }



    @Override
    public EnterOrderRq convertOrderToRequest(long requestId){
        return EnterOrderRq.createNewOrderRq(requestId,this.security.getIsin(),this.orderId,this.entryTime,this.side,this.quantity,this.price,this.broker.getBrokerId(),this.shareholder.getShareholderId(),0,0,this.getStopPrice());
    }
}