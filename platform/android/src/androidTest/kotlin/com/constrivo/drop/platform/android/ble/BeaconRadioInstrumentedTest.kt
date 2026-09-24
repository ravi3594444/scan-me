package com.constrivo.drop.platform.android.ble

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constrivo.drop.core.discovery.BeaconAdvertisement
import com.constrivo.drop.core.discovery.BeaconCarrier
import com.constrivo.drop.core.discovery.Capabilities
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.LocalBeaconState
import com.constrivo.drop.core.discovery.NetworkHint
import com.constrivo.drop.core.discovery.RadioMode
import com.constrivo.drop.core.discovery.Visibility
import com.constrivo.drop.platform.android.crypto.AndroidCryptoProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/**
 * The beacon radio on a device (lab, F-A1/F-A2): advertising and scanning start, or report why not, and never crash,
 * with or without Bluetooth on and permissions granted. With two lab phones running this test side by side each should
 * see the other within the printed time.
 */
@RunWith(AndroidJUnit4::class)
class BeaconRadioInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun advertisesAndScansOrReportsWhyNot() =
        runBlocking {
            val radio = AndroidBeaconRadio(context)
            try {
                val crypto = AndroidCryptoProvider()
                val state = LocalBeaconState(Visibility.EVERYONE, DevicePlatform.PHONE, Capabilities.NONE, NetworkHint.NONE, "Lab phone")
                val advertisement =
                    BeaconAdvertisement.create(
                        crypto,
                        ByteArray(32) {
                            5
                        },
                        state,
                        BeaconCarrier.SERVICE_DATA,
                        System.currentTimeMillis(),
                    )
                radio.startAdvertising(advertisement, RadioMode.FOREGROUND)
                val started = System.currentTimeMillis()
                val heard = withTimeoutOrNull(5_000) { radio.scan(RadioMode.FOREGROUND).firstOrNull() }
                val status = radio.state.first()
                println("drop-lab: $status; first beacon ${heard?.let { "after ${System.currentTimeMillis() - started} ms" } ?: "none"}")
                assertTrue(status.advertising !is AdvertisingStatus.Off)
                radio.stopAdvertising()
            } finally {
                radio.close()
            }
        }
}
