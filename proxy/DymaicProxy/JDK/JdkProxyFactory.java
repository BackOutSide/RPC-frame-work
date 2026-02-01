/**
 * JDK动态代理工厂
 */
public class JdkProxyFactory {
    
    public static Object getProxy(Object target) {
        return Proxy.newProxyInstance(
            target.getClass().getClassLoader(), //目标类的类加载
            target.getClass().getInterfaces(), //代理类实现的接口，可以有多个
            new DebugInvocationHandler(target)); //代理类中自定义的InvocationHandler，用于处理代理类中的方法调用
    }
}
