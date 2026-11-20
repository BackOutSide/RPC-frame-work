package io.github.backoutside.rivet.dianping.api;

public interface ShopQueryService {
    /** Returns null when the shop does not exist. */
    ShopDto findShop(long shopId);
}
