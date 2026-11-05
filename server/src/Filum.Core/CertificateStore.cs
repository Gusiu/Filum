using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;

namespace Filum.Core;

/// <summary>
/// The server certificate and key fingerprint computation (protocol §3.1, ADR-005).
///
/// Filum uses no certificate authority. A self-signed certificate is purely a container
/// for a public key here - authentication is a comparison of the key's fingerprint
/// against a remembered one, on both ends of the connection.
/// </summary>
public sealed class CertificateStore
{
    private const string Subject = "CN=Filum Server";

    /// <summary>
    /// The validity period is deliberately very long. Under key pinning an expiry date
    /// carries no information about trust, and an expired certificate would break a
    /// working installation for no reason.
    /// </summary>
    private static readonly TimeSpan Validity = TimeSpan.FromDays(365 * 50);

    private CertificateStore(X509Certificate2 certificate, string path)
    {
        Certificate = certificate;
        Path = path;
        Pin = ComputePin(certificate);
    }

    public X509Certificate2 Certificate { get; }

    public string Path { get; }

    /// <summary>The server's public key fingerprint: base64url(SHA-256(SPKI)), 43 characters.</summary>
    public string Pin { get; }

    /// <summary>The public key as SubjectPublicKeyInfo in base64url - what <see cref="Pin"/> is the digest of.</summary>
    public string PublicKeyInfo => ToBase64Url(Certificate.PublicKey.ExportSubjectPublicKeyInfo());

    /// <summary>
    /// Signs with the key behind the TLS certificate: ECDSA P-256 over SHA-256, DER-encoded and
    /// in base64url - a form any standard library verifies (protocol §5.7).
    /// </summary>
    public string Sign(string text)
    {
        using var key = Certificate.GetECDsaPrivateKey()
            ?? throw new InvalidOperationException("The server certificate holds no private key.");

        return ToBase64Url(key.SignData(
            Encoding.UTF8.GetBytes(text), HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence));
    }

    /// <summary>
    /// Loads the certificate, or creates it on the first run.
    ///
    /// The file does NOT live in the backup directory - that gets copied to another disk
    /// or restored from a copy, so the private key would travel with the data.
    /// </summary>
    public static CertificateStore LoadOrCreate(string configDirectory)
    {
        Directory.CreateDirectory(LongPath.Extended(configDirectory));
        var path = System.IO.Path.Combine(configDirectory, "server.pfx");
        var extended = LongPath.Extended(path);

        CertificateStore store;
        if (File.Exists(extended))
        {
            var loaded = X509CertificateLoader.LoadPkcs12(
                File.ReadAllBytes(extended), password: null, X509KeyStorageFlags.Exportable);
            store = new CertificateStore(loaded, path);
        }
        else
        {
            var created = Create();
            File.WriteAllBytes(extended, created.Export(X509ContentType.Pkcs12));
            store = new CertificateStore(created, path);
        }

        Publish(configDirectory, store.Certificate);
        return store;
    }

    /// <summary>
    /// Writes server.cer, the certificate without its private key, next to server.pfx. The tray
    /// icon pins the server by it: run as a service, the server runs under another account than
    /// the signed-in user, who may read this file but must not read the key
    /// (server/install/Install-Filum.ps1 sets the access rights).
    /// </summary>
    private static void Publish(string configDirectory, X509Certificate2 certificate)
    {
        var path = LongPath.Extended(System.IO.Path.Combine(configDirectory, PublishedFileName));
        var der = certificate.Export(X509ContentType.Cert);

        // Rewritten in place only when it differs, so the file keeps the rights it was given.
        if (!File.Exists(path) || !File.ReadAllBytes(path).AsSpan().SequenceEqual(der))
            File.WriteAllBytes(path, der);
    }

    /// <summary>The public certificate's file name in the configuration directory.</summary>
    public const string PublishedFileName = "server.cer";

    /// <summary>A certificate held only in memory - for tests.</summary>
    public static CertificateStore InMemory() => new(Create(), path: "");

    private static X509Certificate2 Create()
    {
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);

        var request = new CertificateRequest(Subject, key, HashAlgorithmName.SHA256);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, critical: true));
        request.CertificateExtensions.Add(
            new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, critical: true));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(
            [new Oid("1.3.6.1.5.5.7.3.1"), new Oid("1.3.6.1.5.5.7.3.2")], critical: false));

        var now = DateTimeOffset.UtcNow;
        var certificate = request.CreateSelfSigned(now.AddDays(-1), now.Add(Validity));

        // On Windows the private key has to be transferable into Kestrel's store, which
        // is why we go through PKCS#12 rather than using the object directly.
        return X509CertificateLoader.LoadPkcs12(
            certificate.Export(X509ContentType.Pkcs12), password: null, X509KeyStorageFlags.Exportable);
    }

    /// <summary>
    /// The fingerprint is computed over SubjectPublicKeyInfo rather than the whole
    /// certificate, so a pin survives replacing the certificate while keeping the key pair.
    /// </summary>
    public static string ComputePin(X509Certificate2 certificate) =>
        ToBase64Url(SHA256.HashData(certificate.PublicKey.ExportSubjectPublicKeyInfo()));

    public static string ToBase64Url(byte[] data) =>
        Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
