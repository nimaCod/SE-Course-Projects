package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.MatchResult;
import ir.ramtung.tinyme.domain.entity.Security;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.event.SecurityStateChangedEvent;
import ir.ramtung.tinyme.messaging.request.ChangeMatchingStateRq;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import ir.ramtung.tinyme.repository.SecurityRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MatchingStateHandler {
    @Autowired
    SecurityRepository securityRepository;
    @Autowired
    EventPublisher eventPublisher;
    @Autowired
    Matcher matcher;
    @Autowired
    OrderHandler orderHandler;


    public void handleEnterMatchingState(ChangeMatchingStateRq matchingStateRq) {
        String isin = matchingStateRq.getSecurityIsin();
        Security security = securityRepository.findSecurityByIsin(isin);
        security.setMatchingState(matchingStateRq.getState());
        eventPublisher.publish(new SecurityStateChangedEvent(matchingStateRq.getState() , isin , matchingStateRq.getEntryTime()));
        if(matchingStateRq.getState() == MatchingState.AUCTION){
            MatchResult matchResult =  matcher.execute(security);
            orderHandler.publishTrades(matchResult);
            if (matchResult.trades().size() != 0)
                orderHandler.activateNewAwokeStopLimitOrders(security, matchResult);
        }

    }
}
