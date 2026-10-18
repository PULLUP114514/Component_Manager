namespace ComponentVault.WinUI.Models;

public sealed record StorageLocationRecord(string Id, string Name, string UpdatedAt, bool Deleted)
{
    public override string ToString() => Id is "" or "__unassigned__" ? "未指定库位"
        : string.Equals(Id, Name, StringComparison.Ordinal) ? Id : $"{Id} · {Name}";
}

public sealed record ComponentAllocationRecord(string ComponentId, string LocationId, int Quantity)
{
    public string? LocationName { get; init; }
    public override string ToString() => $"{LocationName ?? LocationId} · {Quantity}";
}

public sealed class StorageLocationDisplay
{
    private readonly IReadOnlyDictionary<string, string> _names;

    public StorageLocationDisplay(IEnumerable<StorageLocationRecord> locations) =>
        _names = locations.ToDictionary(location => location.Id, location => location.Name, StringComparer.Ordinal);

    public string Name(string id) => _names.TryGetValue(id, out var name) && !string.IsNullOrWhiteSpace(name)
        ? name : id;

    public ComponentRecord Decorate(ComponentRecord component) => new()
    {
        Id = component.Id, Sku = component.Sku, Name = component.Name,
        Category = component.Category, PackageName = component.PackageName,
        Location = component.Location, LocationName = Name(component.Location),
        Description = component.Description, Quantity = component.Quantity,
        MinStock = component.MinStock, UpdatedAt = component.UpdatedAt,
        Deleted = component.Deleted, BaseUpdatedAt = component.BaseUpdatedAt,
        CumulativeOutboundQuantity = component.CumulativeOutboundQuantity,
        Allocations = component.Allocations.Select(allocation => allocation with
        {
            LocationName = Name(allocation.LocationId),
        }).ToArray(),
        IsBatchSelected = component.IsBatchSelected,
    };
}
