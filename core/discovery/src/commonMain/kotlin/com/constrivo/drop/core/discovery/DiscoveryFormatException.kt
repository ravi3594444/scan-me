package com.constrivo.drop.core.discovery

/**
 * The one exception every discovery decoder throws for malformed input: the beacon body (architecture §5.1), raw
 * advertising structures and scan responses (§5.1, spec change S11), and mDNS TXT records (§5.4).
 *
 * Decoders never let anything else escape for bad input (no `IndexOutOfBoundsException`, `NumberFormatException`,
 * `CharacterCodingException` and so on), so a scanner can catch this type, count the packet and move on.
 * Programming errors on the encoding side (for example encoding a [Visibility.HIDDEN] beacon) are reported with
 * `IllegalArgumentException` instead, because they are bugs in this device, not input from another one.
 */
open class DiscoveryFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * A beacon body whose version byte names a layout this build cannot read (§5.1 versioning rule: versions
 * `0x10`–`0x7F` are reserved for incompatible layouts). Scanners drop such beacons silently; the peer runs a newer
 * app and is still reachable over mDNS or QR.
 */
class UnsupportedBeaconVersionException(
    val version: Int,
) : DiscoveryFormatException("unsupported beacon layout version 0x${version.toString(16).padStart(2, '0')}")
