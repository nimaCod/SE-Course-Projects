package ir.rumtung.reciever.MessageReciever;

import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Service;

@Service
public class MessageReciver {
    @JmsListener(destination = "learn")
    public void receiveMessage(String message){
        System.out.println("recived a message: " + message);
    }

}
