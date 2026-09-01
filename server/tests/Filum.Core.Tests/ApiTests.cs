using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text;
using Filum.Protocol;

namespace Filum.Core.Tests;

/// <summary>
/// Tests for the HTTP resources in protocol §5. They cover what the unit tests cannot
/// reach: header parsing, response codes and the shape of the error body.
/// </summary>
public class ApiTests : IClassFixture<FilumApp>
{
    private const long Mtime = 1_756_400_000;

    private readonly FilumApp _app;
    private readonly HttpClient _client;
    private readonly string _slug;

    public ApiTests(FilumApp app)
    {
        _app = app;
        _client = app.CreateClient();
        _slug = app.PairAsync().GetAwaiter().GetResult();
    }

    private static string EncodePath(string canonical) =>
        Convert.ToBase64String(Encoding.UTF8.GetBytes(canonical))
               .TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private HttpRequestMessage Put(string canonical, byte[] body, long size, long mtime, long offset,
                                   string? digest = null)
    {
        var request = new HttpRequestMessage(HttpMethod.Put, "/api/v1/file")
        {
            Content = new ByteArrayContent(body)
        };
        request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        request.Headers.TryAddWithoutValidation(FilumHeaders.Path, EncodePath(canonical));
        request.Headers.TryAddWithoutValidation(FilumHeaders.Size, size.ToString());
        request.Headers.TryAddWithoutValidation(FilumHeaders.Mtime, mtime.ToString());
        request.Headers.TryAddWithoutValidation(FilumHeaders.Offset, offset.ToString());
        if (digest is not null) request.Headers.TryAddWithoutValidation(FilumHeaders.Digest, digest);
        return request;
    }

    // --- ping ---

    [Fact]
    public async Task Ping_ReturnsTheProtocolVersionAndLimits()
    {
        var response = await _client.GetFromJsonAsync<PingResponse>("/api/v1/ping");

        Assert.NotNull(response);
        Assert.Equal(ProtocolVersion.Current, response.Protocol);
        Assert.Equal(3, response.Limits.ManifestBatch);
        Assert.True(response.Storage.TotalBytes > 0);
        Assert.NotEmpty(response.Server.InstanceId);
    }

    // --- manifest ---

    [Fact]
    public async Task Manifest_UnknownFile_LandsInNeeded()
    {
        var request = new ManifestRequest("sesja-1", "delta", 0, true,
            [new ManifestEntry("DCIM/Camera/nowy.jpg", 128, Mtime)]);

        var response = await _client.PostAsJsonAsync("/api/v1/manifest", request);
        var body = await response.Content.ReadFromJsonAsync<ManifestResponse>();

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal(0, body!.Have);
        Assert.Equal("DCIM/Camera/nowy.jpg", Assert.Single(body.Needed).Path);
    }

    [Fact]
    public async Task Manifest_UnknownMode_Returns400()
    {
        var request = new ManifestRequest("sesja-1", "lustro", 0, true, []);

        var response = await _client.PostAsJsonAsync("/api/v1/manifest", request);

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task Manifest_OversizedBatch_Returns413()
    {
        var files = Enumerable.Range(0, 4)
            .Select(i => new ManifestEntry($"plik{i}.txt", 1, Mtime))
            .ToList();
        var request = new ManifestRequest("sesja-1", "full", 0, true, files);

        var response = await _client.PostAsJsonAsync("/api/v1/manifest", request);
        var error = await response.Content.ReadFromJsonAsync<ErrorResponse>();

        Assert.Equal(HttpStatusCode.RequestEntityTooLarge, response.StatusCode);
        Assert.Equal(ErrorCodes.PayloadTooLarge, error!.Error);
    }

    // --- put ---

    [Fact]
    public async Task Put_StoresTheFileAndReportsCompletion()
    {
        var content = Encoding.UTF8.GetBytes("file content over HTTP");

        var response = await _client.SendAsync(Put("Documents/przez-http.txt", content, content.Length, Mtime, 0));
        var body = await response.Content.ReadFromJsonAsync<FileChunkResponse>();

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.True(body!.Complete);
        Assert.Equal(content.Length, body.Offset);

        var stored = Path.Combine(_app.Root, _slug, "files", "Documents", "przez-http.txt");
        Assert.Equal(content, await File.ReadAllBytesAsync(stored));
    }

    [Fact]
    public async Task Put_ResumingAtAWrongOffset_Returns409WithTheExpectedOne()
    {
        var content = Encoding.UTF8.GetBytes("0123456789");
        await _client.SendAsync(Put("Movies/wznawiany.mp4", content[..4], content.Length, Mtime, 0));

        var response = await _client.SendAsync(Put("Movies/wznawiany.mp4", content[8..], content.Length, Mtime, 8));
        var error = await response.Content.ReadFromJsonAsync<ErrorResponse>();

        Assert.Equal(HttpStatusCode.Conflict, response.StatusCode);
        Assert.Equal(ErrorCodes.OffsetMismatch, error!.Error);
        Assert.Equal(4, error.ExpectedOffset);
    }

    [Fact]
    public async Task Put_APathEscapingTheDirectory_Returns422()
    {
        var response = await _client.SendAsync(Put("../../ucieczka.txt", [1, 2, 3], 3, Mtime, 0));
        var error = await response.Content.ReadFromJsonAsync<ErrorResponse>();

        Assert.Equal(HttpStatusCode.UnprocessableEntity, response.StatusCode);
        Assert.Equal("traversal", error!.Error);
    }

    [Fact]
    public async Task Put_MissingRequiredHeader_Returns400()
    {
        var request = new HttpRequestMessage(HttpMethod.Put, "/api/v1/file")
        {
            Content = new ByteArrayContent([1, 2, 3])
        };
        request.Headers.TryAddWithoutValidation(FilumHeaders.Path, EncodePath("plik.txt"));
        // brak Size, Mtime, Offset

        var response = await _client.SendAsync(request);

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task Put_BrokenPathEncoding_Returns400()
    {
        var request = new HttpRequestMessage(HttpMethod.Put, "/api/v1/file")
        {
            Content = new ByteArrayContent([1])
        };
        request.Headers.TryAddWithoutValidation(FilumHeaders.Path, "this is not base64url!!!");
        request.Headers.TryAddWithoutValidation(FilumHeaders.Size, "1");
        request.Headers.TryAddWithoutValidation(FilumHeaders.Mtime, Mtime.ToString());
        request.Headers.TryAddWithoutValidation(FilumHeaders.Offset, "0");

        var response = await _client.SendAsync(request);

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task Put_OffsetBeyondTheSize_Returns400()
    {
        var response = await _client.SendAsync(Put("plik.txt", [1], 10, Mtime, 20));

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task FullCycle_TheManifestConfirmsTheFileAfterUpload()
    {
        var content = Encoding.UTF8.GetBytes("a file from the full cycle");
        const string path = "DCIM/Camera/cykl.jpg";

        var before = await (await _client.PostAsJsonAsync("/api/v1/manifest",
            new ManifestRequest("s", "delta", 0, true, [new ManifestEntry(path, content.Length, Mtime)])))
            .Content.ReadFromJsonAsync<ManifestResponse>();
        Assert.Single(before!.Needed);

        await _client.SendAsync(Put(path, content, content.Length, Mtime, 0));

        var after = await (await _client.PostAsJsonAsync("/api/v1/manifest",
            new ManifestRequest("s", "delta", 1, true, [new ManifestEntry(path, content.Length, Mtime)])))
            .Content.ReadFromJsonAsync<ManifestResponse>();

        Assert.Equal(1, after!.Have);
        Assert.Empty(after.Needed);
    }
}
