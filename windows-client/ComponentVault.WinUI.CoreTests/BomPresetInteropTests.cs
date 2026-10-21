using System.Text.Json;
using ComponentVault.WinUI.Services.Bom;
using Xunit;

namespace ComponentVault.WinUI.CoreTests;

public sealed class BomPresetInteropTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "bom-interop-" + Guid.NewGuid().ToString("N"));
    private string PathName => Path.Combine(_root, "bom-presets.json");
    private static BomPreset Preset(string name = "Board") => new(name,
        new("board.csv", null, [new(7, "C1", "MODEL", "0603", 2, "part", "R1,R2")], []),
        "Project", 3, new Dictionary<int, string> { [7] = "" }, []);

    [Fact]
    public void LegacyMigrationBacksUpAndPersistsIdsWithoutLosingExplicitUnmatchedOrRowNumber()
    {
        Directory.CreateDirectory(_root);
        var legacy = JsonSerializer.Serialize(new[] { Preset() });
        File.WriteAllText(PathName, legacy);
        var store = new BomPresetStore(PathName);
        var first = Assert.Single(store.LoadAll());
        var second = Assert.Single(new BomPresetStore(PathName).LoadAll());
        Assert.Equal(first.Id, second.Id);
        Assert.NotEmpty(first.Id);
        Assert.Equal("", second.SelectedComponents[7]);
        Assert.Equal("R1,R2", second.Document.Rows[0].Reference);
        Assert.Equal(legacy, File.ReadAllText(PathName + ".legacy-v1.bak"));
        Assert.Contains("\"version\":2", File.ReadAllText(PathName));
    }

    [Fact]
    public void GroupedAndroidLinesKeepIdsFieldsAndCountQuantityOnce()
    {
        var sheet = new BomPresetSheet("BOM", 2, false);
        var line = new BomPresetLine("android-canonical-key", "C1", "Part", "M", "0603", 5, "chosen", true,
            [new("BOM", 3, 2, new() { ["reference"] = "R1", ["custom"] = "preserved" }), new("BOM", 4, 3, new() { ["reference"] = "R2" })]);
        var record = new BomPresetRecord("android-id", "Board", "2026-10-22T00:00:00Z", false,
            new(1, "Project", 2, "source.xlsx", "abc", [sheet], sheet, ["warning"], [line]));
        var decoded = BomPresetCodec.Decode(record);
        Assert.Equal(5, Assert.Single(decoded.Document.Rows).Quantity);
        var encoded = BomPresetCodec.Encode(decoded, record.Id, record.UpdatedAt);
        Assert.Equal("android-canonical-key", Assert.Single(encoded.Content!.Lines).Id);
        Assert.Equal(5, encoded.Content.Lines[0].QuantityPerSet);
        Assert.Equal(2, encoded.Content.Lines[0].SourceRows.Count);
        Assert.Equal("preserved", encoded.Content.Lines[0].SourceRows[0].Fields["custom"]);
        Assert.True(encoded.Content.Lines[0].Excluded);
        Assert.Equal("chosen", encoded.Content.Lines[0].Selection);
        Assert.Equal("abc", encoded.Content.FileSha256);
    }

    [Fact]
    public void MergePreservesConcurrentEditAndTombstonesAndDoesNotMergeByName()
    {
        var store = new BomPresetStore(PathName);
        store.Save(Preset());
        var snapshot = store.Snapshot();
        var old = Assert.Single(snapshot);
        store.Save(Assert.Single(store.LoadAll()) with { BatchQuantity = 4 });
        var newerRemote = old with { UpdatedAt = "2099-01-01T00:00:00Z", Deleted = true, Content = null };
        store.Merge([newerRemote, old with { Id = "other-id" }], snapshot);
        Assert.Equal(2, store.LoadAll().Count);
        Assert.Equal(4, store.LoadAll().First(item => item.Id == old.Id).BatchQuantity);
        var nextSnapshot = store.Snapshot();
        store.Merge([newerRemote], nextSnapshot);
        Assert.Single(store.LoadAll());
        Assert.Contains(store.Snapshot(), item => item.Id == old.Id && item.Deleted);
    }

    [Fact]
    public void CorruptLegacyFileIsNotOverwrittenOrSilentlyDropped()
    {
        Directory.CreateDirectory(_root);
        const string invalid = "[{\"Name\":\"Bad\"}]";
        File.WriteAllText(PathName, invalid);
        Assert.Throws<InvalidDataException>(() => new BomPresetStore(PathName).LoadAll());
        Assert.Equal(invalid, File.ReadAllText(PathName));
    }
    [Fact]
    public void SharedFixtureKeepsGroupedQuantityAndAllPortableMetadata()
    {
        var directory = new DirectoryInfo(AppContext.BaseDirectory);
        while (directory is not null && !File.Exists(Path.Combine(directory.FullName, "test-data", "bom-preset-v1.json"))) directory = directory.Parent;
        Assert.NotNull(directory);
        var json = File.ReadAllText(Path.Combine(directory!.FullName, "test-data", "bom-preset-v1.json"));
        var envelope = JsonSerializer.Deserialize<BomPresetEnvelope>(json, BomPresetStore.JsonOptions)!;
        var source = Assert.Single(envelope.Presets);
        var decoded = BomPresetCodec.Decode(source);
        Assert.Equal(3, decoded.Document.Rows[0].Quantity);
        Assert.Equal(6, decoded.Document.Rows[0].Quantity * decoded.BatchQuantity);
        Assert.Contains("R1", decoded.Document.Rows[0].Reference);
        Assert.Contains("R2,R3", decoded.Document.Rows[0].Reference);
        var encoded = BomPresetCodec.Encode(decoded, source.Id, source.UpdatedAt);
        Assert.Equal(JsonSerializer.Serialize(source, BomPresetStore.JsonOptions), JsonSerializer.Serialize(encoded, BomPresetStore.JsonOptions));
        Assert.Equal("", encoded.Content!.Lines[1].Selection);
        Assert.True(encoded.Content.Lines[2].Excluded);
    }

    [Fact]
    public void SameSkuRowsWithDifferentExplicitSelectionsRemainSeparate()
    {
        ComponentVault.WinUI.Models.ComponentRecord Component(string id) => new() { Id = id, Sku = id, Name = id, Category = "", PackageName = "", Location = "", Description = "", Quantity = 20, MinStock = 0, UpdatedAt = "2026-10-22T00:00:00Z", Deleted = false };
        var preview = new BomPlanningService().CreatePreview(new("x.csv", null, [new(1, "SAME", null, null, 2), new(2, "SAME", null, null, 3)], []), new("Project", 1), [Component("a"), Component("b")], new Dictionary<int, string> { [1] = "a", [2] = "b" });
        Assert.True(preview.CanConfirm);
        Assert.Equal(2, preview.Lines.Count);
        Assert.Equal(2, preview.Lines.First(line => line.ComponentId == "a").RequiredQuantity);
        Assert.Equal(3, preview.Lines.First(line => line.ComponentId == "b").RequiredQuantity);
    }
    public void Dispose() { if (Directory.Exists(_root)) Directory.Delete(_root, true); }
}
