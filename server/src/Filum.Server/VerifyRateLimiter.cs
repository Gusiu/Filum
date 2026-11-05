using System.Collections.Concurrent;

namespace Filum.Server;

/// <summary>
/// The limit on POST /verify (§5.5). The server reads every listed file in full to answer it,
/// so a paired device runs one verification at a time and at most <see cref="MaxRequests"/>
/// in any <see cref="Window"/>. The phone sends one request per verification the user starts,
/// which stays far below either limit.
/// </summary>
public sealed class VerifyRateLimiter(TimeProvider time)
{
    public static readonly TimeSpan Window = TimeSpan.FromHours(1);
    public const int MaxRequests = 10;

    /// <summary>What a device is told to wait while its previous verification still runs.</summary>
    public static readonly TimeSpan WhileRunning = TimeSpan.FromMinutes(1);

    private readonly ConcurrentDictionary<string, Device> _devices = new(StringComparer.Ordinal);

    /// <summary>
    /// Starts a verification for <paramref name="device"/>, or returns false with how long
    /// it should wait before asking again. A start must be followed by <see cref="Finish"/>.
    /// </summary>
    public bool TryStart(string device, out TimeSpan retryAfter)
    {
        var now = time.GetUtcNow();
        var state = _devices.GetOrAdd(device, _ => new Device());

        lock (state)
        {
            state.Started.RemoveAll(at => now - at >= Window);

            if (state.Running)
            {
                retryAfter = WhileRunning;
                return false;
            }

            if (state.Started.Count >= MaxRequests)
            {
                retryAfter = state.Started[0] + Window - now;
                return false;
            }

            state.Started.Add(now);
            state.Running = true;
            retryAfter = TimeSpan.Zero;
            return true;
        }
    }

    public void Finish(string device)
    {
        if (!_devices.TryGetValue(device, out var state)) return;
        lock (state) state.Running = false;
    }

    private sealed class Device
    {
        public List<DateTimeOffset> Started { get; } = [];
        public bool Running { get; set; }
    }
}
