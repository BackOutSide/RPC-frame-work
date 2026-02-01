/**
 * 主类
 */
public class Main {

    public static void main(String[] args) {
        SmsService smsService = new AliSmsServiceImpl();
        SmsService smsProxy = (SmsService) JdkProxyFactory.getProxy(smsService);
        smsProxy.send("Hello, World!");
    }
}
