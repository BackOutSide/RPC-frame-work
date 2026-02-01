/**
 * 主类
 */
public class Main {
    public static void main(String[] args) {
        SmsService smsService = new AliSmsServiceImpl();
        SmsProxy smsProxy = new SmsProxy(smsService);
        smsProxy.send("Hello, World!");
    }
}
