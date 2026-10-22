using System.Net.Http.Headers;
using ComponentVault.WinUI.Models;

namespace ComponentVault.WinUI.Services;

public sealed partial class SyncApiClient
{
    public async Task<PhotoManifest> GetPhotosAsync(SyncConfiguration settings, string server, string account, CancellationToken cancellation)
    {
        using var request = PhotoRequest(HttpMethod.Get, settings, server, account, "/sync/photos");
        using var response = await _httpClient.SendAsync(request, cancellation);
        if (!response.IsSuccessStatusCode) throw new InvalidOperationException(await BuildErrorMessageAsync(response, cancellation));
        return await DeserializeAsync<PhotoManifest>(response, cancellation);
    }

    public async Task TransferPhotoAsync(SyncConfiguration settings, string server, string account, string key, string? source, string cacheRoot, CancellationToken cancellation, Action? validateBeforeCommit = null)
    {
        using var request = PhotoRequest(source is null ? HttpMethod.Get : HttpMethod.Put, settings, server, account, "/sync/photos/" + key);
        if (source is not null)
        {
            request.Content = new ByteArrayContent(await File.ReadAllBytesAsync(source, cancellation));
            request.Content.Headers.ContentType = new MediaTypeHeaderValue(key.EndsWith(".png") ? "image/png" : "image/jpeg");
        }
        using var response = await _httpClient.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellation);
        if (!response.IsSuccessStatusCode) throw new InvalidOperationException(await BuildErrorMessageAsync(response, cancellation));
        if (source is not null) return;
        if (response.Content.Headers.ContentLength > PrivateProductImageStore.MaxImageBytes) throw new InvalidDataException("照片超过 5 MiB。");
        await using var stream = await response.Content.ReadAsStreamAsync(cancellation);
        using var buffer = new MemoryStream();
        var block = new byte[8192]; int count;
        while ((count = await stream.ReadAsync(block, cancellation)) > 0)
        {
            if (buffer.Length + count > PrivateProductImageStore.MaxImageBytes) throw new InvalidDataException("照片超过 5 MiB。");
            buffer.Write(block, 0, count);
        }
        var bytes = buffer.ToArray();
        var temporaryRoot = Path.Combine(cacheRoot, ".incoming");
        try
        {
            var actual = PrivateProductImageStore.Persist(temporaryRoot, bytes);
            if (actual != key) throw new InvalidDataException("照片内容与引用不一致。");
            Directory.CreateDirectory(cacheRoot);
            cancellation.ThrowIfCancellationRequested();
            validateBeforeCommit?.Invoke();
            File.Move(Path.Combine(temporaryRoot, actual), Path.Combine(cacheRoot, actual), true);
        }
        finally { if (Directory.Exists(temporaryRoot)) Directory.Delete(temporaryRoot, true); }
    }

    private static HttpRequestMessage PhotoRequest(HttpMethod method, SyncConfiguration settings, string server, string account, string path)
    {
        var request = new HttpRequestMessage(method, BuildUri(server, path));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", settings.ApiToken);
        request.Headers.Add("X-Component-Vault-Account-Id", account);
        return request;
    }
}

public sealed record PhotoManifest(int PhotoProtocol, int MaxPhotoBytes, IReadOnlyList<PhotoManifestItem> Photos);
public sealed record PhotoManifestItem(string Key, bool Available, long? SizeBytes);
