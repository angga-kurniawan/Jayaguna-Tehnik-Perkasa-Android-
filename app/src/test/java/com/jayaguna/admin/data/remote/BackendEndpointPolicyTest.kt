package com.jayaguna.admin.data.remote

import com.jayaguna.admin.core.security.BackendException
import org.junit.Assert.*
import org.junit.Test

class BackendEndpointPolicyTest {
    @Test fun localDebugApiDoesNotRequireADeviceIdentityOrEmulatorClassification() {
        for (url in listOf("http://10.0.2.2:8787", "http://localhost:8787", "http://127.0.0.1:8787")) {
            assertEquals(url, BackendEndpointPolicy.node(url, debug = true).toString())
            assertThrows(BackendException::class.java) { BackendEndpointPolicy.node(url, debug = false) }
        }
    }

    @Test fun remoteApiRequiresCleanHttpsOrigin() {
        assertEquals("https://api.example.test", BackendEndpointPolicy.node("https://api.example.test", debug = false).toString())
        for (value in listOf("http://api.example.test", "https://user:password@api.example.test", "https://api.example.test/base", "https://api.example.test?token=value", "https://api.example.test#fragment", "https://api.example.test:70000")) {
            assertThrows(value, BackendException::class.java) { BackendEndpointPolicy.node(value, debug = true) }
        }
    }
}
