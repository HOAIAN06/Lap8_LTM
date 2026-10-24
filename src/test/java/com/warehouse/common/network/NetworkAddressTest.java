package com.warehouse.common.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NetworkAddressTest {
    @Test
    void rejectsNullAndBlankIp() {
        assertFalse(NetworkAddress.isIpv4(null));
        assertFalse(NetworkAddress.isIpv4(""));
        assertFalse(NetworkAddress.isIpv4("   "));
    }

    @Test
    void acceptsValidIpv4() {
        assertTrue(NetworkAddress.isIpv4("192.168.1.1"));
        assertTrue(NetworkAddress.isIpv4("10.0.0.1"));
        assertTrue(NetworkAddress.isIpv4("127.0.0.1"));
        assertTrue(NetworkAddress.isIpv4("0.0.0.0"));
        assertTrue(NetworkAddress.isIpv4("255.255.255.255"));
    }

    @Test
    void rejectsMalformedIpv4() {
        assertFalse(NetworkAddress.isIpv4("192.168.1"));
        assertFalse(NetworkAddress.isIpv4("192.168.1.1.1"));
        assertFalse(NetworkAddress.isIpv4("192.168.1.256"));
        assertFalse(NetworkAddress.isIpv4("192.168.-1.1"));
        assertFalse(NetworkAddress.isIpv4("192.168.01.1")); // leading zero
        assertFalse(NetworkAddress.isIpv4("abc.def.ghi.jkl"));
        assertFalse(NetworkAddress.isIpv4("192.168.1.1/24"));
    }
}
