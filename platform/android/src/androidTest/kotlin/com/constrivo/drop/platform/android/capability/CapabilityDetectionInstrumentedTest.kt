package com.constrivo.drop.platform.android.capability

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constrivo.drop.core.discovery.Capabilities
import com.constrivo.drop.platform.android.crypto.AndroidCryptoProvider
import com.constrivo.drop.platform.android.permission.RadioPermissions
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame

/**
 * F-A4 on a device (lab): detection runs without crashing; the lab compares the printed lines with the device's
 * datasheet ("flags match the device's real hardware on all lab devices").
 */
@RunWith(AndroidJUnit4::class)
class CapabilityDetectionInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun detectsAndMapsThisDevice() {
        val detector = AndroidCapabilityDetector(context, AndroidCryptoProvider())
        detector.start()
        try {
            val inputs = detector.readInputs()
            val capabilities = CapabilityMapping.capabilities(inputs)
            assertFalse(Capabilities.Flag.DESKTOP_WITHOUT_BLUETOOTH in capabilities)
            assertNotSame(LocalRadioFacts.UNKNOWN, detector.facts.value)
            println("drop-lab: inputs $inputs")
            println("drop-lab: $capabilities, facts ${detector.facts.value}")
            println("drop-lab: permissions ${RadioPermissions.read(context)}")
        } finally {
            detector.stop()
        }
    }
}
