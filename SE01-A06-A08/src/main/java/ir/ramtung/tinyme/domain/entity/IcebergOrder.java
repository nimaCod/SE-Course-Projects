package ir.ramtung.tinyme.domain.entity;

import java.time.LocalDateTime;

import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class IcebergOrder extends Order {
    int peakSize;
    int displayedQuantity;

    public IcebergOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, LocalDateTime entryTime, int peakSize, int displayedQuantity, OrderStatus status) {
        super(orderId, security, side, quantity, price, broker, shareholder, entryTime, status);
        this.peakSize = peakSize;
        this.displayedQuantity = displayedQuantity;
    }

    public IcebergOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, LocalDateTime entryTime, int peakSize, int displayedQuantity, OrderStatus status, int minimumExecutionQuantity) {
        super(orderId, security, side, quantity, price, broker, shareholder, entryTime, status, minimumExecutionQuantity);
        this.peakSize = peakSize;
        this.displayedQuantity = displayedQuantity;
    }

    public IcebergOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, LocalDateTime entryTime, int peakSize, OrderStatus status) {
        this(orderId, security, side, quantity, price, broker, shareholder, entryTime, peakSize, Math.min(peakSize, quantity), status);
    }

    public IcebergOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, LocalDateTime entryTime, int peakSize, OrderStatus status, int minimumExecutionQuantity) {
        this(orderId, security, side, quantity, price, broker, shareholder, entryTime, peakSize, Math.min(peakSize, quantity), status, minimumExecutionQuantity);
    }

    public IcebergOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, LocalDateTime entryTime, int peakSize) {
        this(orderId, security, side, quantity, price, broker, shareholder, entryTime, peakSize, OrderStatus.NEW);
    }

    public IcebergOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, LocalDateTime entryTime, int peakSize,int minimumExecutionQuantith ) {
        this(orderId, security, side, quantity, price, broker, shareholder, entryTime, peakSize, OrderStatus.NEW, minimumExecutionQuantith);
    }

    public IcebergOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, int peakSize) {
        super(orderId, security, side, quantity, price, broker, shareholder);
        this.peakSize = peakSize;
        this.displayedQuantity = Math.min(peakSize, quantity);
    }

    public IcebergOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, int peakSize, int minimumExecutionQuantity) {
        super(orderId, security, side, quantity, price, broker, shareholder, minimumExecutionQuantity);
        this.peakSize = peakSize;
        this.displayedQuantity = Math.min(peakSize, quantity);
    }

    public int getWholeQuantity(){
        return super.quantity;
    }

    @Override
    public Order snapshot() {
        return new IcebergOrder(orderId, security, side, quantity, price, broker, shareholder, entryTime, peakSize, OrderStatus.SNAPSHOT, minimumExecutionQuantity);
    }

    @Override
    public Order snapshotWithQuantity(int newQuantity) {
        return new IcebergOrder(orderId, security, side, newQuantity, price, broker, shareholder, entryTime, peakSize, OrderStatus.SNAPSHOT, minimumExecutionQuantity);
    }

    @Override
    public int getQuantity() {
        if (status == OrderStatus.NEW)
            return super.getQuantity();
        return displayedQuantity;
    }

    @Override
    public void decreaseQuantity(int amount) {
        if (status == OrderStatus.NEW) {
            super.decreaseQuantity(amount);
            return;
        }
        if (amount > displayedQuantity)
            throw new IllegalArgumentException();
        quantity -= amount;
        displayedQuantity -= amount;
    }

    public void replenish() {
        displayedQuantity = Math.min(quantity, peakSize);
    }

    @Override
    public void updateFromRequest(EnterOrderRq updateOrderRq) {
        super.updateFromRequest(updateOrderRq);
        if (peakSize < updateOrderRq.getPeakSize()) {
            displayedQuantity = Math.min(quantity, updateOrderRq.getPeakSize());
        }
        peakSize = updateOrderRq.getPeakSize();
    }
    @Override
    public EnterOrderRq convertOrderToRequest(long requestId){
        return EnterOrderRq.createNewOrderRq(requestId,this.security.getIsin(),this.orderId,this.entryTime,this.side,this.quantity,this.price,this.broker.getBrokerId(),this.shareholder.getShareholderId(),this.getPeakSize()); // TODO what is request ID ?
    }
}
