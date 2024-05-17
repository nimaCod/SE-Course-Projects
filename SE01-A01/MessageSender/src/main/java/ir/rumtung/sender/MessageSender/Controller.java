package ir.rumtung.sender.MessageSender;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Controller {
    @Autowired
    SendMessageService sendMessageService;

    @GetMapping(value = "/queue/sendMessage")
    public void sendMessage(@RequestParam("message") String message){
        System.out.println("Message is: "+message);
        sendMessageService.SendMessage(message);
    }
}
