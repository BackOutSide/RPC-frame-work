/**
 * 阿里短信服务实现
 */
public class AliSmsServiceImpl implements SmsService {
    
    @Override
    public String send(String message) {
        System.out.println("send message by ali sms: " + message);
        return message;
    }
}
