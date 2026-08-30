package pl.wspa.filum.crypto

import android.security.keystore.KeyGenParameterSpec
import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import javax.net.ssl.KeyManager
import javax.security.auth.x500.X500Principal

/**
 * Tożsamość tego telefonu wobec serwera: para kluczy w Android Keystore i certyfikat
 * samopodpisany, którym telefon przedstawia się w mTLS (§3.1 protokołu, ADR-005).
 *
 * Klucz prywatny powstaje wewnątrz Keystore i **nigdy go nie opuszcza** — nie da się go
 * odczytać nawet po zrootowaniu telefonu, jeśli urządzenie ma StrongBox. Aplikacja
 * operuje wyłącznie uchwytem; podpisywanie wykonuje system.
 *
 * Certyfikat generuje sam Keystore przy tworzeniu klucza, więc nie potrzebujemy
 * BouncyCastle ani żadnej biblioteki do budowy X.509.
 */
/**
 * Gdzie fizycznie mieszka klucz prywatny.
 *
 * Rozróżnienie ma znaczenie praktyczne, nie tylko opisowe. StrongBox to osobny układ
 * scalony odporny na atak fizyczny; TEE to wydzielony tryb tego samego procesora —
 * znacznie lepszy niż nic, ale podatny na klasę ataków, na którą StrongBox nie jest.
 * Urządzenia z niższej półki zwykle mają wyłącznie TEE.
 */
enum class KeySecurity {
    /** Osobny element bezpieczny (Samsung Knox Vault i podobne). */
    STRONGBOX,

    /** Zaufane środowisko wykonawcze procesora. */
    TRUSTED_ENVIRONMENT,

    /** Klucz w pamięci procesu — nie powinno się zdarzyć na Androidzie 11 i nowszym. */
    SOFTWARE,

    /** Nie udało się ustalić. */
    UNKNOWN;

    val isHardwareBacked: Boolean get() = this == STRONGBOX || this == TRUSTED_ENVIRONMENT
}

class DeviceIdentity private constructor(
    val alias: String,
    val certificate: X509Certificate,
    private val privateKey: PrivateKey,
    /** Gdzie leży klucz prywatny. Pokazywane użytkownikowi i zapisywane w dzienniku. */
    val security: KeySecurity,
) {

    /** Certyfikat w postaci DER — dokładnie to, co idzie w polu `cert` żądania parowania. */
    val certificateDer: ByteArray get() = certificate.encoded

    /** Odcisk klucza publicznego tego urządzenia; serwer zna go pod nazwą `deviceKey`. */
    val pin: String by lazy { KeyPin.of(certificate) }

    /**
     * Menedżer klucza dla mTLS.
     *
     * Świadomie NIE używamy [javax.net.ssl.KeyManagerFactory]: standardowa implementacja
     * wybiera certyfikat na podstawie listy urzędów, których serwer oczekuje. Nasz serwer
     * żadnej listy nie wysyła (bo nie ma urzędu — ADR-005), więc domyślny menedżer
     * nie wybrałby niczego i uścisk dłoni poszedłby bez certyfikatu klienta.
     */
    fun keyManagers(): Array<KeyManager> = arrayOf(FixedKeyManager(alias, certificate, privateKey))

    companion object {
        private const val TAG = "FilumIdentity"
        private const val KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "filum-device-key"

        /** Ważność bez znaczenia przy przypięciu klucza — patrz [KeyPin]. Byle nie wygasła. */
        private const val VALIDITY_YEARS = 50

        fun exists(alias: String = DEFAULT_ALIAS): Boolean = keyStore().containsAlias(alias)

        /**
         * Wczytuje tożsamość albo tworzy ją przy pierwszym parowaniu.
         *
         * Klucz przeżywa aktualizacje aplikacji, ale nie odinstalowanie — wtedy telefon
         * traci tożsamość i wymaga ponownego sparowania. To zamierzone: odinstalowanie
         * aplikacji ma unieważniać jej dostęp do kopii zapasowej.
         */
        fun loadOrCreate(alias: String = DEFAULT_ALIAS): DeviceIdentity {
            val store = keyStore()
            if (!store.containsAlias(alias)) return generate(alias)

            val certificate = store.getCertificate(alias) as? X509Certificate
            val key = store.getKey(alias, null) as? PrivateKey

            if (certificate == null || key == null) {
                // Wpis jest niekompletny — Keystore potrafi tak skończyć po nieudanej
                // aktualizacji systemu. Odtwarzamy od zera; skutkiem jest ponowne parowanie.
                Log.w(TAG, "Niekompletny wpis Keystore dla $alias, generuję od nowa")
                store.deleteEntry(alias)
                return generate(alias)
            }

            return DeviceIdentity(alias, certificate, key, securityOf(key))
        }

        fun delete(alias: String = DEFAULT_ALIAS) {
            val store = keyStore()
            if (store.containsAlias(alias)) store.deleteEntry(alias)
        }

        private fun generate(alias: String): DeviceIdentity {
            // StrongBox to osobny układ scalony; jest na S24 Ultra, ale nie na każdym
            // urządzeniu, a niektóre implementacje odmawiają części parametrów.
            // Brak StrongBoxa nie może uniemożliwić działania aplikacji.
            val strongBox = tryGenerate(alias, useStrongBox = true)
            if (strongBox) Log.i(TAG, "Klucz urządzenia w StrongBox")
            else {
                tryGenerate(alias, useStrongBox = false)
                Log.i(TAG, "Klucz urządzenia w zwykłym Keystore (brak StrongBox)")
            }

            val store = keyStore()
            val key = store.getKey(alias, null) as PrivateKey

            return DeviceIdentity(
                alias = alias,
                certificate = store.getCertificate(alias) as X509Certificate,
                privateKey = key,
                // Pytamy Keystore, zamiast zapamiętywać wynik generowania. Ta wartość
                // jest odczytywana przy każdym uruchomieniu, a nie tylko przy pierwszym,
                // i tylko Keystore wie, co naprawdę zrobił z kluczem.
                security = securityOf(key),
            )
        }

        private fun tryGenerate(alias: String, useStrongBox: Boolean): Boolean = try {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
            generator.initialize(specFor(alias, useStrongBox))
            generator.generateKeyPair()
            true
        } catch (e: StrongBoxUnavailableException) {
            if (!useStrongBox) throw e
            false
        } catch (e: Exception) {
            if (!useStrongBox) throw e
            Log.w(TAG, "StrongBox odmówił wygenerowania klucza, próbuję bez niego", e)
            false
        }

        private fun specFor(alias: String, useStrongBox: Boolean): KeyGenParameterSpec {
            val notBefore = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, VALIDITY_YEARS) }

            return KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                // TLS 1.3 podpisuje przez ecdsa_secp256r1_sha256; pozostałe skróty
                // dokładamy, żeby nie polec na starszym uzgodnieniu wersji.
                .setDigests(
                    KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA384,
                    KeyProperties.DIGEST_SHA512,
                )
                .setCertificateSubject(X500Principal("CN=Filum Android Client"))
                .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotBefore(notBefore.time)
                .setCertificateNotAfter(notAfter.time)
                // Synchronizacja idzie w tle, w nocy, przy zablokowanym ekranie —
                // wymóg uwierzytelnienia użytkownika zatrzymałby ją na pierwszym pliku.
                .setUserAuthenticationRequired(false)
                .setIsStrongBoxBacked(useStrongBox)
                .build()
        }

        /**
         * Odczytuje z Keystore, gdzie faktycznie leży klucz.
         *
         * Do Androida 11 włącznie da się jedynie stwierdzić, czy klucz jest w sprzęcie —
         * odróżnienie StrongBoxa od TEE wprowadzono dopiero w Androidzie 12. Wtedy
         * zwracamy TRUSTED_ENVIRONMENT, bo to jest gwarancja, której jesteśmy pewni.
         */
        private fun securityOf(key: PrivateKey): KeySecurity = try {
            val info = KeyFactory.getInstance(key.algorithm, KEYSTORE)
                .getKeySpec(key, KeyInfo::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeySecurity.STRONGBOX
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeySecurity.TRUSTED_ENVIRONMENT
                    KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeySecurity.SOFTWARE
                    else -> KeySecurity.UNKNOWN
                }
            } else {
                @Suppress("DEPRECATION")
                if (info.isInsideSecureHardware) KeySecurity.TRUSTED_ENVIRONMENT else KeySecurity.SOFTWARE
            }
        } catch (e: Exception) {
            Log.w(TAG, "Nie udało się ustalić poziomu zabezpieczenia klucza", e)
            KeySecurity.UNKNOWN
        }

        private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    }
}
