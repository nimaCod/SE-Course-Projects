package ir.ramtung.tinyme.messaging.event;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import ir.ramtung.tinyme.domain.entity.Trade;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import lombok.Data;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@NoArgsConstructor
public class TradeEvent extends Event{
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    LocalDateTime time;
    String securityIsin;
    int quantity;
    int price;
    long buyId;
    long sellId;
    public TradeEvent(LocalDateTime _time , Trade trade)
    {
        time = _time;
        securityIsin= trade.getSecurity().getIsin();
        quantity = trade.getQuantity();
        price = trade.getPrice();;
        buyId = trade.getBuy().getOrderId();
        sellId= trade.getSell().getOrderId();
    }

}
