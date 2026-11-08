package com.tucontrol.data.tv.androidtv

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.tucontrol.data.network.TrustAllTls
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Calendar
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509KeyManager
import javax.security.auth.x500.X500Principal

/**
 * Holds the watch's client identity for Android TV Remote v2.
 *
 * The RSA key lives in the Android Keystore (it never leaves secure hardware) and the
 * Keystore self-signs the certificate, so no extra crypto library is required. The TV
 * remembers this certificate after PIN pairing.
 */
class AndroidTvCertificateStore {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    val certificate: X509Certificate
        get() {
            ensureKey()
            return keyStore.getCertificate(ALIAS) as X509Certificate
        }

    val publicKey: RSAPublicKey get() = certificate.publicKey as RSAPublicKey

    fun socketFactory(): SSLSocketFactory {
        ensureKey()
        val context = SSLContext.getInstance("TLS")
        context.init(arrayOf(KeystoreKeyManager()), arrayOf(TrustAllTls.trustManager), SecureRandom())
        return context.socketFactory
    }

    private fun ensureKey() {
        if (keyStore.containsAlias(ALIAS)) return
        val validFrom = Calendar.getInstance()
        val validTo = Calendar.getInstance().apply { add(Calendar.YEAR, CERTIFICATE_YEARS) }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setKeySize(KEY_SIZE)
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
            .setCertificateSubject(X500Principal("CN=TuControl"))
            .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
            .setCertificateNotBefore(validFrom.time)
            .setCertificateNotAfter(validTo.time)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE).run {
            initialize(spec)
            generateKeyPair()
        }
    }

    /** Presents the Keystore-backed identity during the TLS handshake. */
    private inner class KeystoreKeyManager : X509KeyManager {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun getCertificateChain(alias: String?) = arrayOf(certificate)
        override fun getPrivateKey(alias: String?) = keyStore.getKey(ALIAS, null) as PrivateKey
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "tucontrol_androidtv_remote"
        const val KEY_SIZE = 2048
        const val CERTIFICATE_YEARS = 10
    }
}
