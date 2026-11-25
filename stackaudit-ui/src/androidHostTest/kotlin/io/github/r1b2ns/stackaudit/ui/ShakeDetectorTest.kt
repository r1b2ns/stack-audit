package io.github.r1b2ns.stackaudit.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ShakeDetectorTest {
    @Test
    fun firesOnStrongShakeWithCooldown() {
        var shakes = 0
        val detector = ShakeDetector(threshold = 2.7f, cooldownMs = 1000) { shakes++ }
        detector.onAcceleration(0f, 0f, 9.8f, now = 10_000)
        assertEquals(0, shakes)
        detector.onAcceleration(30f, 0f, 9.8f, now = 10_100)
        assertEquals(1, shakes)
        detector.onAcceleration(30f, 0f, 9.8f, now = 10_500)
        assertEquals(1, shakes)
        detector.onAcceleration(30f, 0f, 9.8f, now = 11_200)
        assertEquals(2, shakes)
    }
}
