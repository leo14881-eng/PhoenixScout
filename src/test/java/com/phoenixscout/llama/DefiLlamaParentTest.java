package com.phoenixscout.llama;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 子协议没有 gecko_id 时用母协议的（2026-10-18：Fluid Lending/DEX → parent#fluid → instadapp）。 */
class DefiLlamaParentTest {

    @Test
    void parsesParentGeckoIds() throws Exception {
        var j = new ObjectMapper().readTree("{\"protocols\":[],\"parentProtocols\":["
                + "{\"id\":\"parent#fluid\",\"name\":\"Fluid\",\"gecko_id\":\"instadapp\"},"
                + "{\"id\":\"parent#nogecko\",\"name\":\"N\",\"gecko_id\":null}]}");
        var m = DefiLlamaClient.parseParentGeckoIds(j);
        assertEquals("instadapp", m.get("parent#fluid"));
        assertFalse(m.containsKey("parent#nogecko"));
        assertTrue(DefiLlamaClient.parseParentGeckoIds(new ObjectMapper().readTree("[]")).isEmpty());
    }
}
