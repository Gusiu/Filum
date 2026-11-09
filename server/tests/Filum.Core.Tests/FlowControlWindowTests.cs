using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;

namespace Filum.Core.Tests;

/// <summary>The server's HTTP/2 flow-control windows (docs/MEASUREMENTS.md §6).</summary>
public class FlowControlWindowTests
{
    [Fact]
    public void ALargeFile_MayRunAheadOfTheServerBy8MB()
    {
        // Kestrel's defaults - 768 KB a stream, 1 MB a connection - let the phone have only
        // some 25 ms of a fast transfer in flight, and on the test phone a single large file
        // went 1.4 to 2.4 times slower with them than with these windows.
        using var app = new FilumApp();

        var http2 = app.Services.GetRequiredService<IOptions<KestrelServerOptions>>().Value.Limits.Http2;

        Assert.Equal(8 * 1024 * 1024, http2.InitialStreamWindowSize);
        Assert.Equal(32 * 1024 * 1024, http2.InitialConnectionWindowSize);
    }
}
