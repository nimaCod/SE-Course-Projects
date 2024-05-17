package ir.rumtung.BalanceKeeper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

@Service
public class Application {
    @Autowired
    BalanceHandler balanceHandler;
    @Autowired
    JmsTemplate jmsTemplate;

    public static String dest = "OUTQ";

    @JmsListener(destination = "INQ")
    public void receiveMessage(String message){
        System.out.println("received a message: " + message);
        String sendMessage =  balanceHandler.handle(message);
        jmsTemplate.convertAndSend(dest,sendMessage);
    }
}



