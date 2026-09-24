package dev.example.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class SessionArchiveTest {
    @Test
    void routesReleaseCaptureToItsOwnPrivateObjectKey() {
        assertEquals("release/deploy-42/capture.webm",
                SessionArchive.objectKey(new SessionArchive.Start("deploy-42", "release", "oncall")));
        assertThrows(IllegalArgumentException.class,
                () -> SessionArchive.objectKey(new SessionArchive.Start("deploy-42", "marketing", "oncall")));
    }
}
