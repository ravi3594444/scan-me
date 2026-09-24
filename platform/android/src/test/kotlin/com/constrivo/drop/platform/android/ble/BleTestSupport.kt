package com.constrivo.drop.platform.android.ble

import com.constrivo.drop.core.crypto.JcaCryptoProvider
import com.constrivo.drop.core.discovery.AdvertisingFormat
import com.constrivo.drop.core.discovery.BeaconAdvertisement
import com.constrivo.drop.core.discovery.BeaconCarrier
import com.constrivo.drop.core.discovery.Capabilities
import com.constrivo.drop.core.discovery.DevicePlatform
import com.constrivo.drop.core.discovery.IpAddress
import com.constrivo.drop.core.discovery.LocalBeaconState
import com.constrivo.drop.core.discovery.NetworkHint
import com.constrivo.drop.core.discovery.NetworkLinkInfo
import com.constrivo.drop.core.discovery.Visibility

/** Shared fixtures of the BLE tests: advertisements built by core/discovery, and the bytes Android puts on air. */
internal object BleTestSupport {
    val crypto = JcaCryptoProvider()
    val secret = ByteArray(32) { (it + 1).toByte() }

    /** Unix time inside epoch 1_000_000 / 900 (any fixed instant works). */
    const val NOW: Long = 1_758_700_000_000L

    val hint: NetworkHint = NetworkHint.derive(crypto, NetworkLinkInfo(gateways = listOf(IpAddress.parse("192.168.1.1"))))

    fun state(
        visibility: Visibility = Visibility.EVERYONE,
        nickname: String = "Ana's Pixel",
        capabilities: Capabilities = Capabilities.of(Capabilities.Flag.WIFI_5GHZ, Capabilities.Flag.WIFI_DIRECT),
    ) = LocalBeaconState(visibility, DevicePlatform.PHONE, capabilities, hint, nickname)

    fun advertisement(
        visibility: Visibility = Visibility.EVERYONE,
        nickname: String = "Ana's Pixel",
        carrier: BeaconCarrier = BeaconCarrier.SERVICE_DATA,
        now: Long = NOW,
    ): BeaconAdvertisement = BeaconAdvertisement.create(crypto, secret, state(visibility, nickname), carrier, now)

    /**
     * The advertising data Android emits for [spec] (AOSP `AdvertiseHelper` order: Flags, which the stack adds to
     * connectable sets; manufacturer data; the 16-bit UUID list; service data).
     */
    fun onAir(spec: AdvertisingSetSpec): ByteArray {
        var out = ByteArray(0)
        if (spec.connectable) out += byteArrayOf(2, AdvertisingFormat.AD_TYPE_FLAGS.toByte(), AdvertisingFormat.FLAGS_VALUE.toByte())
        spec.manufacturerData?.let { out += manufacturer(it) }
        if (spec.includeServiceUuid) {
            out +=
                byteArrayOf(3, AdvertisingFormat.AD_TYPE_COMPLETE_16BIT_UUIDS.toByte()) + le16(AdvertisingFormat.SERVICE_UUID_16)
        }
        spec.serviceData?.let {
            out +=
                byteArrayOf((it.size + 3).toByte(), AdvertisingFormat.AD_TYPE_SERVICE_DATA_16BIT.toByte()) +
                le16(AdvertisingFormat.SERVICE_UUID_16) +
                it
        }
        return out
    }

    /** The scan response Android emits for [spec], or empty. */
    fun scanResponseOnAir(spec: AdvertisingSetSpec): ByteArray = spec.scanResponseManufacturerData?.let(::manufacturer) ?: ByteArray(0)

    private fun manufacturer(data: ByteArray): ByteArray =
        byteArrayOf((data.size + 3).toByte(), AdvertisingFormat.AD_TYPE_MANUFACTURER_DATA.toByte()) + le16(AdvertisingFormat.COMPANY_ID) +
            data

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())

    /** A legacy scan record as `ScanRecord.getBytes()` returns it: 31 + 31 bytes, zero-padded. */
    fun legacyRecord(
        advertising: ByteArray,
        scanResponse: ByteArray = ByteArray(0),
    ): ByteArray = advertising.copyOf(31) + scanResponse.copyOf(31)
}
