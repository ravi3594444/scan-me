package com.constrivo.drop.core.ladder

/**
 * A ladder input could not be used. Every check in this module that looks at data from the peer or from a platform
 * provider reports a problem with one of these types, never with an index or arithmetic exception.
 */
open class LadderException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Wi-Fi credentials break the rules of the link they are for (architecture §8 notes): a Wi-Fi Direct network name
 * that does not start with `DIRECT-` and two letters or digits or is longer than 32 bytes, or a passphrase that is not
 * 8–63 printable ASCII characters. The message never contains the passphrase.
 */
class LinkCredentialsException(
    message: String,
) : LadderException(message)
