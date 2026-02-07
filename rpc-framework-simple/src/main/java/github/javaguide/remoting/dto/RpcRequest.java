package github.javaguide.remoting.dto;

import lombok.*;

import java.io.Serializable;


/**
 * RPC请求请求实体
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Builder
@ToString
public class RpcRequest implements Serializable {

    // 序列化ID
    private static final long serialVersionUID = 1905122041950251207L;
    // 请求ID
    private String requestId;
    // 接口名称
    private String interfaceName;
    // 方法名称
    private String methodName;
    // 方法参数
    private Object[] parameters;
    // 方法参数类型
    private Class<?>[] paramTypes;
    // 版本
    private String version;
    // 组 用来处理一个接口有多个实现类的情况
    private String group;

    /**
     * 获取RPC服务名称
     * @return
     */
    public String getRpcServiceName() {
        return this.getInterfaceName() + this.getGroup() + this.getVersion();
    }
}
