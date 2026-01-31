import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Builder;
import lombok.ToString;
import java.io.Serializable;



@AllArgsConstructor
@Getter
@NoArgsConstructor
@Builder
@ToString

/**
 * 响应类
 */

public class RpcResponse {

    //响应的消息
   private String message;
}