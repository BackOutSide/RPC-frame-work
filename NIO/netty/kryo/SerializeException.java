import lombok.extern.slf4j.Slf4j;`

/**
 * 序列化异常
 */
public class SerializeException extends RuntimeException {
    public SerializeException(String message) {
        super(message);
    }
}