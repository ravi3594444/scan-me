# platform/android consumer rules (merged into the app's R8 configuration).
#
# AndroidCryptoProvider calls BouncyCastle's lightweight classes directly (org.bouncycastle.math.ec.rfc7748.X25519,
# org.bouncycastle.math.ec.rfc8032.Ed25519, org.bouncycastle.crypto.modes.ChaCha20Poly1305); nothing is looked up by
# reflection and no JCA provider is registered, so no keep rules are needed and R8 drops the rest of bcprov.
# bcprov also contains LDAP and JNDI helpers that reference classes Android does not ship; they are unreachable.
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.jsse.**
