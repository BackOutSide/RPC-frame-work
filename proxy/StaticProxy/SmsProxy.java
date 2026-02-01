/**
 * 短信代理
 */
public class SmsProxy implements SmsService {
    private final SmsService smsService;

    public SmsProxy(SmsService smsService) {
        this.smsService = smsService;
    }

    @Override
    public String send(String message) {
        //调用方法前，我们可以添加自己的操作
        System.out.println("send message start");
        return smsService.send(message);
        //调用方法之后，我们可以添加自己的操作
        System.out.println("send message end");
        return null;
    }
}
