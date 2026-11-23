package io.github.backoutside.rivet.dianping.api;

import io.github.backoutside.rivet.annotation.RpcIdempotent;

public interface ShopQueryService {
    /** Returns null when the shop does not exist. */
    @RpcIdempotent
    ShopDto findShop(long shopId);
}
