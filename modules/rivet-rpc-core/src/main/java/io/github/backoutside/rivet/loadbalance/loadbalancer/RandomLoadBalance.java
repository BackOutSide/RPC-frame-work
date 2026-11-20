package io.github.backoutside.rivet.loadbalance.loadbalancer;

import io.github.backoutside.rivet.loadbalance.AbstractLoadBalance;
import io.github.backoutside.rivet.remoting.dto.RpcRequest;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Implementation of random load balancing strategy
 *
 * @author shuang.kou
 * @createTime 2020年06月21日 07:47:00
 */
public class RandomLoadBalance extends AbstractLoadBalance {
    @Override
    protected String doSelect(List<String> serviceAddresses, RpcRequest rpcRequest) {
        return serviceAddresses.get(ThreadLocalRandom.current().nextInt(serviceAddresses.size()));
    }
}
