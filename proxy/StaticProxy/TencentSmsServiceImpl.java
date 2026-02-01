/**
 * 腾讯云短信服务实现
 */
public class TencentSmsServiceImpl implements SmsService {

    @Override
    public String send(String message) {
        System.out.println("send message by tencent sms: " + message);
        return message;
    }
}
