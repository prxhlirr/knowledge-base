package com.boyang.search.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiEngineGatewayTimeoutConfigTest {

    @Test
    void resolveTimeoutMsUsesDefaultWhenBlank() {
        AiEngineGateway gateway = new AiEngineGateway();

        assertEquals(3000, gateway.resolveTimeoutMs(null, 3000));
        assertEquals(3000, gateway.resolveTimeoutMs("", 3000));
        assertEquals(3000, gateway.resolveTimeoutMs("   ", 3000));
    }

    @Test
    void resolveTimeoutMsUsesConfiguredPositiveValue() {
        AiEngineGateway gateway = new AiEngineGateway();

        assertEquals(4500, gateway.resolveTimeoutMs("4500", 3000));
        assertEquals(4500, gateway.resolveTimeoutMs(" 4500 ", 3000));
    }

    @Test
    void resolveTimeoutMsFallsBackForInvalidValue() {
        AiEngineGateway gateway = new AiEngineGateway();

        assertEquals(3000, gateway.resolveTimeoutMs("abc", 3000));
    }

    @Test
    void resolveTimeoutMsFallsBackForZeroOrNegativeValue() {
        AiEngineGateway gateway = new AiEngineGateway();

        assertEquals(3000, gateway.resolveTimeoutMs("0", 3000));
        assertEquals(3000, gateway.resolveTimeoutMs("-1", 3000));
    }
}
