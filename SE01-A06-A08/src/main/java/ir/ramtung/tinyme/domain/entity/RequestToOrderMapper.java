package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.request.EnterOrderRq;

import java.util.HashMap;
import java.util.Map;

public class RequestToOrderMapper {
    Map<Long, Long> map;
    public RequestToOrderMapper()
    {
        map = new HashMap<>();
    }

    public void addNewIdMap(EnterOrderRq enterOrderRq)
    {
        map.put(enterOrderRq.getOrderId() , enterOrderRq.getRequestId());
    }

    public Long getRequestID(long orderID)
    {
        return map.get(orderID);
    }

    public void removeMap(long orderID)
    {
        map.remove(orderID);
    }

}
