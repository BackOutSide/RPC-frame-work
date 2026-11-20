package io.github.backoutside.rivet.dianping.provider;

final class VoucherKeys {
    static final String STREAM = "dianping:orders:stream";
    static final String GROUP = "order-workers";

    private VoucherKeys() {
    }

    static String stock(long voucherId) {
        return "dianping:voucher:" + voucherId + ":stock";
    }

    static String metadata(long voucherId) {
        return "dianping:voucher:" + voucherId + ":meta";
    }

    static String accepted(long voucherId, long userId) {
        return "dianping:voucher:" + voucherId + ":accepted:" + userId;
    }

    static String order(String orderId) {
        return "dianping:order:" + orderId;
    }
}
