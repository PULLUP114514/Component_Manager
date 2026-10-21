using System.Collections.Concurrent;
using System.Text.Json;

namespace ComponentVault.WinUI.Services.Bom;

public sealed record BomPreset(string Name, BomDocument Document, string ProjectName, int BatchQuantity,
    Dictionary<int, string> SelectedComponents, List<int> SkippedRows, string Id = "", string UpdatedAt = "")
{
    public override string ToString() => Name;
}

public sealed class BomPresetStore
{
    private static readonly ConcurrentDictionary<string, object> Gates = new(StringComparer.OrdinalIgnoreCase);
    public static readonly JsonSerializerOptions JsonOptions = new() { PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower, PropertyNameCaseInsensitive = true };
    private readonly string _path;
    private readonly object _gate;
    public BomPresetStore(string? path = null)
    {
        _path = Path.GetFullPath(path ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ComponentVault", "bom-presets.json"));
        _gate = Gates.GetOrAdd(_path, _ => new object());
    }
    public IReadOnlyList<BomPreset> LoadAll() => Snapshot().Where(item => !item.Deleted).Select(BomPresetCodec.Decode).OrderBy(item => item.Name, StringComparer.OrdinalIgnoreCase).ToArray();
    public IReadOnlyList<BomPresetRecord> Snapshot() { lock (_gate) return Read(); }
    private List<BomPresetRecord> Read()
    {
        if (!File.Exists(_path)) return [];
        try
        {
            var json = File.ReadAllText(_path);
            using var root = JsonDocument.Parse(json);
            if (root.RootElement.ValueKind == JsonValueKind.Array)
            {
                var legacy = JsonSerializer.Deserialize<List<BomPreset>>(json) ?? throw new InvalidDataException("预设内容为空。");
                var now = DateTimeOffset.UtcNow.ToString("O");
                var migrated = legacy.Select(item => BomPresetCodec.Encode(item, Guid.NewGuid().ToString("N"), now)).ToList();
                if (!File.Exists(_path + ".legacy-v1.bak")) File.Copy(_path, _path + ".legacy-v1.bak", false);
                Write(migrated); return migrated;
            }
            var envelope = JsonSerializer.Deserialize<BomPresetEnvelope>(json, JsonOptions) ?? throw new InvalidDataException("预设内容为空。");
            if (envelope.Version != 2 || envelope.Presets is null) throw new InvalidDataException("预设文件版本无效。");
            foreach (var item in envelope.Presets) BomPresetCodec.Validate(item);
            if (envelope.Presets.Select(item => item.Id).Distinct(StringComparer.Ordinal).Count() != envelope.Presets.Count) throw new InvalidDataException("预设 ID 重复。");
            return envelope.Presets.ToList();
        }
        catch (Exception error) when (error is JsonException or IOException or UnauthorizedAccessException or InvalidDataException or OverflowException or NullReferenceException or ArgumentException)
        { throw new InvalidDataException("BOM 预设无法读取；原文件已保留，请先备份或修复。", error); }
    }
    public void Save(BomPreset preset)
    {
        if (string.IsNullOrWhiteSpace(preset.Name) || preset.Name.Trim().Length > 100) throw new ArgumentException("预设名称无效。");
        lock (_gate)
        {
            var records = Read();
            var previous = string.IsNullOrEmpty(preset.Id) ? records.FirstOrDefault(item => !item.Deleted && string.Equals(item.Name, preset.Name.Trim(), StringComparison.OrdinalIgnoreCase)) : records.FirstOrDefault(item => item.Id == preset.Id);
            var record = BomPresetCodec.Encode(preset with { Name = preset.Name.Trim() }, previous?.Id ?? (string.IsNullOrWhiteSpace(preset.Id) ? Guid.NewGuid().ToString("N") : preset.Id), NextTimestamp(previous?.UpdatedAt));
            records.RemoveAll(item => item.Id == record.Id); records.Add(record); Write(records);
        }
    }
    private static string NextTimestamp(string? previous)
    {
        var now = DateTimeOffset.UtcNow;
        if (DateTimeOffset.TryParse(previous, out var prior) && now < prior.AddMilliseconds(1)) now = prior.AddMilliseconds(1);
        return now.ToString("O");
    }
    public bool Delete(string name) { lock (_gate) { var item = Read().FirstOrDefault(item => !item.Deleted && item.Name == name); return item is not null && DeleteById(item.Id); } }
    public bool DeleteById(string id)
    {
        lock (_gate)
        {
            var records = Read(); var index = records.FindIndex(item => item.Id == id && !item.Deleted);
            if (index < 0) return false;
            records[index] = records[index] with { Deleted = true, Content = null, UpdatedAt = NextTimestamp(records[index].UpdatedAt) };
            Write(records); return true;
        }
    }
    public void Merge(IReadOnlyList<BomPresetRecord> remote, IReadOnlyList<BomPresetRecord> outbound)
    {
        lock (_gate)
        {
            var records = Read();
            foreach (var incoming in remote)
            {
                BomPresetCodec.Validate(incoming);
                var index = records.FindIndex(item => item.Id == incoming.Id);
                if (index < 0) { records.Add(incoming); continue; }
                var local = records[index];
                var snapshot = outbound.FirstOrDefault(item => item.Id == local.Id);
                if (snapshot is null || JsonSerializer.Serialize(local, JsonOptions) != JsonSerializer.Serialize(snapshot, JsonOptions)) continue;
                if (DateTimeOffset.Parse(incoming.UpdatedAt) >= DateTimeOffset.Parse(local.UpdatedAt)) records[index] = incoming;
            }
            Write(records);
        }
    }
    private void Write(IReadOnlyList<BomPresetRecord> presets)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
        var temporary = _path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try { File.WriteAllText(temporary, JsonSerializer.Serialize(new BomPresetEnvelope(2, presets), JsonOptions)); File.Move(temporary, _path, true); }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }
}
