using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace Filum.Core;

/// <summary>
/// Certyfikat serwera i wyliczanie odcisków kluczy (§3.1 protokołu, ADR-005).
///
/// Filum nie używa urzędu certyfikacji. Certyfikat samopodpisany jest tu wyłącznie
/// pojemnikiem na klucz publiczny — uwierzytelnienie polega na porównaniu odcisku
/// klucza z zapamiętanym, po obu stronach połączenia.
/// </summary>
public sealed class CertificateStore
{
    private const string Subject = "CN=Filum Server";

    /// <summary>
    /// Ważność jest celowo bardzo długa. Przy przypięciu klucza data wygaśnięcia nie
    /// niesie żadnej informacji o zaufaniu, a wygasły certyfikat zerwałby działającą
    /// instalację bez powodu.
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

    /// <summary>Odcisk klucza publicznego serwera: base64url(SHA-256(SPKI)), 43 znaki.</summary>
    public string Pin { get; }

    /// <summary>
    /// Wczytuje certyfikat albo tworzy go przy pierwszym uruchomieniu.
    ///
    /// Plik NIE leży w katalogu backupu — ten bywa kopiowany na inny dysk albo
    /// odtwarzany z kopii, więc klucz prywatny wyciekłby razem z danymi.
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

    /// <summary>Certyfikat trzymany tylko w pamięci — do testów.</summary>
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

        // Na Windows klucz prywatny musi dać się przenieść do magazynu Kestrela,
        // dlatego przechodzimy przez PKCS#12 zamiast używać obiektu wprost.
        return X509CertificateLoader.LoadPkcs12(
            certificate.Export(X509ContentType.Pkcs12), password: null, X509KeyStorageFlags.Exportable);
    }

    /// <summary>
    /// Odcisk liczony z SubjectPublicKeyInfo, a nie z całego certyfikatu. Dzięki temu
    /// przypięcie przetrwa wymianę certyfikatu przy zachowaniu tej samej pary kluczy.
    /// </summary>
    public static string ComputePin(X509Certificate2 certificate) =>
        ToBase64Url(SHA256.HashData(certificate.PublicKey.ExportSubjectPublicKeyInfo()));

    public static string ToBase64Url(byte[] data) =>
        Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
