using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services;
using ComponentVault.WinUI.ViewModels;
using Xunit;

namespace ComponentVault.WinUI.CoreTests;

public sealed class ComponentEditorViewModelTests
{
    [Fact]
    public void ExistingComponent_InitializesEditingFieldsWithoutCatalogOrigin()
    {
        var existing = new ComponentRecord
        {
            Id = "cmp-1", Sku = "C200", Name = "Original", Category = "Resistor",
            PackageName = "0603", Location = "A", Description = "notes", Quantity = 8,
            MinStock = 2, UpdatedAt = "2026-01-01T00:00:00Z", Deleted = false,
        };
        var editor = new ComponentEditorViewModel(existing, [Location("A")], "NEW");

        Assert.Equal("Original", editor.Name);
        Assert.Equal(8, editor.Quantity);
        Assert.Equal("A", editor.SelectedLocationId);
        Assert.False(editor.UseNewLocation);
        Assert.Equal(ComponentEditSource.Manual, editor.Source);
        Assert.True(editor.TryCreateRequest(out var request, out _));
        Assert.Equal(existing.Id, request!.Draft.Id);
    }

    [Fact]
    public void CatalogOriginFollowsCatalogSku_AndAllowsFieldEdits()
    {
        var editor = new ComponentEditorViewModel(null, [Location("A")], "NEW")
        {
            Sku = "C201", Name = "Catalog", Category = "Resistor",
            PackageName = "0603", Description = "官方商品页：https://example.invalid",
            Quantity = 3, SelectedLocationId = "A",
        };
        Assert.Equal(ComponentEditSource.Manual, editor.Source);
        editor.RecordCatalogFields("C201");
        Assert.Equal(ComponentEditSource.Catalog, editor.Source);
        editor.Name = "My own name";
        editor.Description = "Edited note";
        Assert.Equal(ComponentEditSource.Catalog, editor.Source);
        editor.Sku = "C202";
        Assert.Equal(ComponentEditSource.Manual, editor.Source);
        Assert.True(editor.TryCreateRequest(out var request, out _));
        Assert.Equal(ComponentEditSource.Manual, request!.Source);

        editor.Sku = "C201";
        Assert.Equal(ComponentEditSource.Catalog, editor.Source);
        editor.RecordCatalogFields("C999");
        Assert.Equal(ComponentEditSource.Manual, editor.Source);
    }

    [Fact]
    public void NewLocationRemainsDraftUntilCommit_AndRequestValidationIsFocused()
    {
        var editor = new ComponentEditorViewModel(null, [], "NEW")
        {
            Sku = "C202", Name = "Part", Category = "Resistor",
            PackageName = "0603", Quantity = 2, NewLocationName = "Bin B",
        };
        Assert.True(editor.UseNewLocation);
        Assert.True(editor.TryCreateRequest(out var request, out _));
        Assert.Equal("NEW", request!.Draft.Location);
        Assert.Equal("Bin B", request.NewLocationName);

        editor.Quantity = double.NaN;
        Assert.False(editor.TryCreateRequest(out _, out _));
        Assert.Equal("Bin B", editor.NewLocationName);
        Assert.Equal("C202", editor.Sku);
        editor.Quantity = 2;
        editor.NewLocationName = " ";
        Assert.False(editor.TryCreateRequest(out _, out _));
        editor.NewLocationName = "Bin B";
        Assert.True(editor.TryCreateRequest(out var retry, out _));
        Assert.Equal("NEW", retry!.Draft.Location);
    }

    private static StorageLocationRecord Location(string id) =>
        new(id, "Bin " + id, "2026-01-01T00:00:00Z", false);
}
