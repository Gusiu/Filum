using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

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

        if (File.Exists(extended))
        {
            var loaded = X509CertificateLoader.LoadPkcs12(
                File.ReadAllBytes(extended), password: null, X509KeyStorageFlags.Exportable);
            return new CertificateStore(loaded, path);
        }

        var created = Create();
        File.WriteAllBytes(extended, created.Export(X509ContentType.Pkcs12));
        return new CertificateStore(created, path);
    }

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
