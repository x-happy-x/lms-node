package ru.mrcrubs.lmsnode.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
@ActiveProfiles("test")
class PropertiesBindingTest {

    @Autowired
    private NodeProperties nodeProperties;

    @Autowired
    private HmacAuthProperties authProperties;

    @Test
    void shouldBindNodePropertiesFromTestProfile() {
        assertEquals("./build/downloads-test", nodeProperties.getDownloadDir());
        assertEquals(1, nodeProperties.getMaxParallel());
    }

    @Test
    void shouldBindAuthPropertiesFromTestProfile() {
        assertEquals(120, authProperties.getAllowedSkewSeconds());
        assertEquals(600, authProperties.getNonceTtlSeconds());
        assertEquals("test-secret", authProperties.getClients().get("router-main"));
        assertFalse(authProperties.getClients().isEmpty());
    }
}
