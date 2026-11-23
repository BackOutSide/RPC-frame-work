package io.github.backoutside.rivet.enums;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpcStatusCodeTest {
    @Test
    void publishedCodesAreUniqueAndKeepLegacyValues() {
        Set<Integer> values = Arrays.stream(RpcStatusCode.values())
                .map(RpcStatusCode::getCode)
                .collect(Collectors.toSet());

        assertEquals(RpcStatusCode.values().length, values.size());
        assertEquals(200, RpcStatusCode.SUCCESS.getCode());
        assertEquals(500, RpcStatusCode.LEGACY_FAIL.getCode());
        assertEquals(RpcResponseCodeEnum.OVERLOADED.getCode(), RpcStatusCode.OVERLOADED.getCode());
    }

    @Test
    void onlyProviderStatusesCanBeSentInResponses() {
        assertTrue(RpcStatusCode.SERVICE_NOT_FOUND.isWireResponse());
        assertFalse(RpcStatusCode.RESPONSE_TIMEOUT.isWireResponse());
        assertFalse(RpcStatusCode.PROTOCOL_ERROR.isWireResponse());
        assertNull(RpcStatusCode.fromCode(8801));
    }
}
