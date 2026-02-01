import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 调试代理类
 */

public class DebugInvocationHandler implements InvocationHandler {

    /**
     * 代理类中的真实对象
     */
    private final Object target;

    public DebugInvocationHandler(Object target) {
        this.target = target;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws InvocationTargetException, IllegalAccessException {
        try {
            System.out.println("before method: " + method.getName());
            Object result = method.invoke(target, args);
            System.out.println("after method: " + method.getName());
            return result;
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }
}