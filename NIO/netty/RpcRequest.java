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
 * 请求类
 */
/
public class RpcRequest {
    
    //请求的接口名
    private String interfaceName;
    
    //请求的方法名
    private String methodName;
}