using System.Net;
using System.Text;
using System.Text.Json;
using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services;
using Xunit;

namespace ComponentVault.WinUI.CoreTests;

public sealed class PhotoSyncTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "photo-sync-" + Guid.NewGuid());
    private static readonly byte[] Png = Convert.FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aU1sAAAAASUVORK5CYII=");
    [Fact]
    public async Task OriginalUploadsAndMissingCopyDownloadsWhileCacheClearPreservesOriginal()
    {
        Directory.CreateDirectory(_root);
        var store = new InventoryStore(Path.Combine(_root, "inventory.db")); store.Initialize();
        store.SaveSyncConfiguration("https://photos.test", "", "token", false);
        var key = PrivateProductImageStore.Persist(Path.Combine(_root, "images"), Png);
        store.SaveComponent(new ComponentDraft { Sku = "PHOTO", Name = "Photo", Category = "test", PackageName = "0603", Location = "A", Quantity = 0, MinStock = 0, Description = PrivateProductImageStore.MarkerPrefix + key });
        bool available = false; int uploads = 0; int downloads = 0;
        var client = new SyncApiClient(new HttpClient(new Handler(async request =>
        {
            Assert.Equal("token", request.Headers.Authorization!.Parameter);
            Assert.Equal("account", Assert.Single(request.Headers.GetValues("X-Component-Vault-Account-Id")));
            if (request.RequestUri!.AbsolutePath == "/sync/photos") return Json(new { photo_protocol = 1, max_photo_bytes = 5242880, photos = new[] { new { key, available, size_bytes = Png.Length } } });
            if (request.Method == HttpMethod.Put) { Assert.Equal(Png, await request.Content!.ReadAsByteArrayAsync()); available = true; uploads++; return Json(new { key, size_bytes = Png.Length }); }
            downloads++; return new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(Png) };
        })));
        var service = new PhotoSyncService(store, client);
        var result = new SyncRunResult { IsSuccess = true, Message = "", PhotoProtocol = 1, UsedServerBaseUrl = "https://photos.test", Identity = new SyncAccountIdentity { ServerId = "server", AccountId = "account", Name = "test", Role = "admin" } };
        await service.RunAsync(store.GetSyncConfiguration(), result, default);
        Assert.Equal(1, uploads); Assert.Equal("complete", service.ReadState().State);
        service.ClearCache(); Assert.True(File.Exists(Path.Combine(_root, "images", key)));
        File.Move(Path.Combine(_root, "images", key), Path.Combine(_root, key));
        await service.RunAsync(store.GetSyncConfiguration(), result, default);
        Assert.Equal(1, downloads);
        Assert.Equal(Png, File.ReadAllBytes(PrivateProductImageStore.Resolve(Path.Combine(_root, "images"), PrivateProductImageStore.MarkerPrefix + key)!));
        service.ClearCache(); Assert.Contains("待处理 1", service.Summary); Assert.True(File.Exists(Path.Combine(_root, key)));
    }
    [Fact]
    public async Task UnsupportedServerPreservesLocalReferenceAndPendingState()
    {
        Directory.CreateDirectory(_root); var store = new InventoryStore(Path.Combine(_root, "inventory.db")); store.Initialize();
        var service = new PhotoSyncService(store, new SyncApiClient());
        var summary = await service.RunAsync(store.GetSyncConfiguration(), new SyncRunResult { IsSuccess = true, Message = "" }, default);
        Assert.Contains("服务器暂不支持", summary); Assert.Equal("unsupported", new PhotoSyncService(store, new SyncApiClient()).ReadState().State);
    }
    [Fact]
    public async Task CancellationPersistsPendingAndPropagates()
    {
        Directory.CreateDirectory(_root); var store = new InventoryStore(Path.Combine(_root, "inventory.db")); store.Initialize();
        var service = new PhotoSyncService(store, new SyncApiClient());
        using var cancellation = new CancellationTokenSource(); cancellation.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => service.RunAsync(store.GetSyncConfiguration(), new SyncRunResult { IsSuccess = true, Message = "", PhotoProtocol = 1 }, cancellation.Token));
        Assert.Equal("pending", service.ReadState().State);
    }
    private static HttpResponseMessage Json(object value) => new(HttpStatusCode.OK) { Content = new StringContent(JsonSerializer.Serialize(value), Encoding.UTF8, "application/json") };
    private sealed class Handler(Func<HttpRequestMessage, Task<HttpResponseMessage>> send) : HttpMessageHandler
    { protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) => send(request); }
    public void Dispose() { Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools(); if (Directory.Exists(_root)) Directory.Delete(_root, true); }
}
