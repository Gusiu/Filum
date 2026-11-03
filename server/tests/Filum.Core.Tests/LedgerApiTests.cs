using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using Filum.Protocol;
using Microsoft.Extensions.DependencyInjection;

namespace Filum.Core.Tests;

/// <summary>
/// GET /api/v1/ledger (protocol §5.7): the device's ledger with the server's signed statement
/// of its length and head - what the phone needs to witness it (§9.5).
///
/// A class fixture is a server of its own, so the ledger holds exactly the files these
/// tests send.
/// </summary>
public class LedgerApiTests : IClassFixture<FilumApp>, IAsyncLifetime
{
    private const long Mtime = 1_756_400_000;

    private readonly FilumApp _app;
    private readonly HttpClient _client;

    public LedgerApiTests(FilumApp app)
    {
        _app = app;
        _client = app.CreateClient();
    }

    public async Task InitializeAsync()
    {
        await _app.PairAsync();

        // Three accepted files, sent once for the whole class: the fixture keeps its ledger.
        var current = await _client.GetFromJsonAsync<LedgerResponse>("/api/v1/ledger");
        if (current!.Entries > 0) return;

        for (var i = 1; i <= 3; i++)
        {
            var content = Encoding.UTF8.GetBytes($"witnessed content {i}");
            var response = await _client.SendAsync(Put($"Ledger/file{i}.txt", content));
            response.EnsureSuccessStatusCode();
        }
    }

    public Task DisposeAsync() => Task.CompletedTask;

    private static HttpRequestMessage Put(string canonical, byte[] content)
    {
        var request = new HttpRequestMessage(HttpMethod.Put, "/api/v1/file") { Content = new ByteArrayContent(content) };
        request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        request.Headers.TryAddWithoutValidation(FilumHeaders.Path,
            Convert.ToBase64String(Encoding.UTF8.GetBytes(canonical)).TrimEnd('=').Replace('+', '-').Replace('/', '_'));
        request.Headers.TryAddWithoutValidation(FilumHeaders.Size, content.Length.ToString());
        request.Headers.TryAddWithoutValidation(FilumHeaders.Mtime, Mtime.ToString());
        request.Headers.TryAddWithoutValidation(FilumHeaders.Offset, "0");
        // Only a file with a digest goes into the ledger (§9.1).
        request.Headers.TryAddWithoutValidation(FilumHeaders.Digest, Convert.ToHexStringLower(SHA256.HashData(content)));
        return request;
    }

    private static string Hex(string line) => Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(line)));

    private static byte[] FromBase64Url(string text)
    {
        var padded = text.Replace('-', '+').Replace('_', '/');
        return Convert.FromBase64String(padded + new string('=', (4 - padded.Length % 4) % 4));
    }

    [Fact]
    public async Task TheWholeLedger_ComesWithItsHead()
    {
        var ledger = await _client.GetFromJsonAsync<LedgerResponse>("/api/v1/ledger");

        Assert.Equal(3, ledger!.Entries);
        Assert.Equal(1, ledger.From);
        Assert.Equal(3, ledger.Lines.Count);
        Assert.Equal(Hex(ledger.Lines[2]), ledger.Head);
    }

    [Fact]
    public async Task TheStatement_IsSignedWithTheKeyThePinNames()
    {
        var ledger = await _client.GetFromJsonAsync<LedgerResponse>("/api/v1/ledger");
        var certificates = _app.Services.GetRequiredService<CertificateStore>();
        var key = FromBase64Url(ledger!.Key);

        // The key the phone is handed must be the one its pin fingerprints.
        Assert.Equal(certificates.Pin, CertificateStore.ToBase64Url(SHA256.HashData(key)));

        using var verifier = ECDsa.Create();
        verifier.ImportSubjectPublicKeyInfo(key, out _);
        var statement = LedgerStatement.Text(
            certificates.Pin, CertificateStore.ComputePin(_app.ClientCertificate), ledger.Entries, ledger.Head, ledger.At);

        Assert.True(verifier.VerifyData(Encoding.UTF8.GetBytes(statement), FromBase64Url(ledger.Signature),
            HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence));
    }

    [Fact]
    public async Task ALaterStart_ReturnsOnlyTheRest()
    {
        var ledger = await _client.GetFromJsonAsync<LedgerResponse>("/api/v1/ledger?from=2");

        Assert.Equal(3, ledger!.Entries);
        Assert.Equal(2, ledger.From);
        Assert.Equal(2, ledger.Lines.Count);
        Assert.Contains("\"n\":2", ledger.Lines[0]);
    }

    [Fact]
    public async Task AStartBeyondTheEnd_ReturnsNoLines_ButTheLength()
    {
        var ledger = await _client.GetFromJsonAsync<LedgerResponse>("/api/v1/ledger?from=50");

        Assert.Equal(3, ledger!.Entries);
        Assert.Empty(ledger.Lines);
    }

    [Fact]
    public async Task AStartBelowOne_Returns400()
    {
        var response = await _client.GetAsync("/api/v1/ledger?from=0");

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }
}
