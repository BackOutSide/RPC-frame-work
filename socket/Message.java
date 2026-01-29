import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;

/**
 * 消息类
 */

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Message implements Serializable {
    private String content;
}
