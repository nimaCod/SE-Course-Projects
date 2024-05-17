package ir.rumtung.sender.MessageSender;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

@Service
public class SendMessageService {
    @Autowired
    JmsTemplate jmsTemplate;
    public static String dest = "learn";
    public void SendMessage(String message){
        jmsTemplate.convertAndSend(dest,message);
        System.out.println("Message: " + message + " sent on " + dest);
    }
}
