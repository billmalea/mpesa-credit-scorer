package com.ttacs.scorer.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoSecurityTest {

    @Test
    void allowsOnlyDemoStaticAssets() {
        assertTrue(DemoSecurity.isAllowedStaticPath("/"));
        assertTrue(DemoSecurity.isAllowedStaticPath("/index.html"));
        assertTrue(DemoSecurity.isAllowedStaticPath("/app.js"));
        assertTrue(DemoSecurity.isAllowedStaticPath("/styles.css"));
        assertTrue(DemoSecurity.isAllowedStaticPath("/samples/amina-strong-inflow.csv"));
        assertFalse(DemoSecurity.isAllowedStaticPath("/policy.yml"));
        assertFalse(DemoSecurity.isAllowedStaticPath("/../policy.yml"));
        assertFalse(DemoSecurity.isAllowedStaticPath("/samples/extracted/secret.txt"));
        assertFalse(DemoSecurity.isAllowedStaticPath("/samples/../app.js"));
        assertFalse(DemoSecurity.isAllowedStaticPath("/samples/nested/path.csv"));
    }
}
