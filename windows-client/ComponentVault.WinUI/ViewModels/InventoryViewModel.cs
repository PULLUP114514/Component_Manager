using System.Collections.ObjectModel;
using ComponentVault.WinUI.Localization;
using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services;

namespace ComponentVault.WinUI.ViewModels;

/// <summary>Owns the inventory list, its filters, and selection across store refreshes.</summary>
public sealed class InventoryViewModel : ObservableObject
{
    private const string AllCategoriesOption = "全部分类";
    private const string AllLocationsOption = "全部仓位";
    private const string SortUpdatedDescOption = "最近更新";
    private const string SortNameAscOption = "名称 A-Z";
    private const string SortLowStockFirstOption = "库存紧张优先";
    private const string SortQuantityDescOption = "库存数量";

    private readonly InventoryStore _store;
    private bool _updatingOptions;
    private IReadOnlyList<ComponentRecord> _allComponents = [];
    private Dictionary<string, string> _locationIdsByLabel = new(StringComparer.Ordinal);
    private string _componentSearchText = string.Empty;
    private bool _showLowStockOnly;
    private string _selectedComponentCategoryFilter = AllCategoriesOption;
    private string _selectedComponentLocationFilter = AllLocationsOption;
    private string _selectedComponentSortOption = SortUpdatedDescOption;
    private ComponentRecord? _selectedComponent;

    public InventoryViewModel(InventoryStore store)
    {
        _store = store;
        Components = [];
        InventoryCategoryOptions = [];
        InventoryLocationOptions = [];
        InventorySortOptions = new ObservableCollection<string>(
            [SortUpdatedDescOption, SortLowStockFirstOption, SortNameAscOption, SortQuantityDescOption]);
    }

    public ObservableCollection<ComponentRecord> Components { get; }
    public ObservableCollection<string> InventoryCategoryOptions { get; }
    public ObservableCollection<string> InventoryLocationOptions { get; }
    public ObservableCollection<string> InventorySortOptions { get; }
    public IReadOnlyList<ComponentRecord> AvailableComponents => _allComponents;

    public string ComponentSearchText
    {
        get => _componentSearchText;
        set { if (SetProperty(ref _componentSearchText, value)) ApplyFilter(); }
    }

    public bool ShowLowStockOnly
    {
        get => _showLowStockOnly;
        set { if (SetProperty(ref _showLowStockOnly, value)) ApplyFilter(); }
    }

    public string SelectedComponentCategoryFilter
    {
        get => _selectedComponentCategoryFilter;
        set { if (!_updatingOptions && SetProperty(ref _selectedComponentCategoryFilter, value ?? AllCategoriesOption)) ApplyFilter(); }
    }

    public string SelectedComponentLocationFilter
    {
        get => _selectedComponentLocationFilter;
        set { if (!_updatingOptions && SetProperty(ref _selectedComponentLocationFilter, value ?? AllLocationsOption)) ApplyFilter(); }
    }

    public string SelectedComponentSortOption
    {
        get => _selectedComponentSortOption;
        set { if (!_updatingOptions && SetProperty(ref _selectedComponentSortOption, value ?? SortUpdatedDescOption)) ApplyFilter(); }
    }

    public ComponentRecord? SelectedComponent
    {
        get => _selectedComponent;
        set => SetProperty(ref _selectedComponent, value);
    }

    public void Refresh()
    {
        var components = _store.GetComponents();
        var outboundTotals = _store.GetCumulativeOutboundQuantities();
        var display = new StorageLocationDisplay(_store.GetStorageLocations(includeDeleted: true));
        _allComponents = components.Select(component => display.Decorate(new ComponentRecord
        {
            Id = component.Id,
            Sku = component.Sku,
            Name = component.Name,
            Category = component.Category,
            PackageName = component.PackageName,
            Location = component.Location,
            Description = component.Description,
            Quantity = component.Quantity,
            MinStock = component.MinStock,
            UpdatedAt = component.UpdatedAt,
            Deleted = component.Deleted,
            CumulativeOutboundQuantity = outboundTotals.GetValueOrDefault(component.Id),
            Allocations = component.Allocations,
            BaseUpdatedAt = component.BaseUpdatedAt,
        })).ToArray();
        UpdateFilterOptions();
        ApplyFilter();
        OnPropertyChanged(nameof(AvailableComponents));
    }

    private void ApplyFilter()
    {
        IEnumerable<ComponentRecord> filtered = _allComponents;
        if (ShowLowStockOnly)
            filtered = filtered.Where(component => component.IsLowStock);
        if (SelectedComponentCategoryFilter != AllCategoriesOption)
            filtered = filtered.Where(component => CategoryFilterSemantics.SameCategory(component.Category, SelectedComponentCategoryFilter));
        if (SelectedComponentLocationFilter != AllLocationsOption)
        {
            var locationId = _locationIdsByLabel.GetValueOrDefault(SelectedComponentLocationFilter,
                SelectedComponentLocationFilter);
            filtered = filtered.Where(component => component.Location == locationId);
        }
        if (!string.IsNullOrWhiteSpace(ComponentSearchText))
        {
            var query = ComponentSearchText.Trim();
            filtered = filtered.Where(component => InventorySearch.Matches(component, query)
                || CategoryFilterSemantics.MatchesSearch(component.Category, query)
                || component.Allocations.Any(allocation => allocation.LocationName is not null
                    && allocation.LocationName.Contains(query, StringComparison.OrdinalIgnoreCase)));
        }
        filtered = SelectedComponentSortOption switch
        {
            SortLowStockFirstOption => filtered.OrderBy(component => component.IsLowStock ? 0 : 1)
                .ThenBy(component => component.Quantity - component.MinStock).ThenBy(component => component.Name),
            SortNameAscOption => filtered.OrderBy(component => component.Name),
            SortQuantityDescOption => filtered.OrderByDescending(component => component.Quantity).ThenBy(component => component.Name),
            _ => filtered.OrderByDescending(component => component.UpdatedAt),
        };
        var selectedId = SelectedComponent?.Id;
        Components.Clear();
        foreach (var component in filtered) Components.Add(component);

        SelectedComponent = Components.Count == 0 ? null
            : Components.FirstOrDefault(component => component.Id == selectedId) ?? Components[0];
        OnPropertyChanged(nameof(Components));
    }

    private void UpdateFilterOptions()
    {
        _updatingOptions = true;
        try
        {
            var categories = CategoryFilterSemantics.Options(_allComponents.Select(component => component.Category));
            ReplaceOptions(InventoryCategoryOptions, new[] { AllCategoriesOption }.Concat(categories));
            if (_selectedComponentCategoryFilter != AllCategoriesOption)
            {
                var equivalent = categories.FirstOrDefault(option =>
                    CategoryFilterSemantics.SameCategory(option, _selectedComponentCategoryFilter));
                if (equivalent is not null && !_selectedComponentCategoryFilter.Equals(equivalent, StringComparison.Ordinal))
                {
                    _selectedComponentCategoryFilter = equivalent;
                    OnPropertyChanged(nameof(SelectedComponentCategoryFilter));
                }
            }
            if (!InventoryCategoryOptions.Contains(_selectedComponentCategoryFilter, StringComparer.OrdinalIgnoreCase))
            {
                _selectedComponentCategoryFilter = AllCategoriesOption;
                OnPropertyChanged(nameof(SelectedComponentCategoryFilter));
            }

            var oldLocationId = _locationIdsByLabel.GetValueOrDefault(
                _selectedComponentLocationFilter, _selectedComponentLocationFilter);
            var locations = _allComponents.Select(component => component.Location)
                .Where(location => !string.IsNullOrWhiteSpace(location))
                .Distinct(StringComparer.Ordinal).ToArray();
            var displayNames = _allComponents.GroupBy(component => component.Location,
                StringComparer.Ordinal).ToDictionary(group => group.Key,
                group => group.First().DisplayLocation, StringComparer.Ordinal);
            var duplicateNames = displayNames.Values.GroupBy(name => name,
                StringComparer.OrdinalIgnoreCase).Where(group => group.Count() > 1)
                .Select(group => group.Key).ToHashSet(StringComparer.OrdinalIgnoreCase);
            _locationIdsByLabel = locations.ToDictionary(id =>
                duplicateNames.Contains(displayNames[id]) || displayNames[id] == AllLocationsOption
                    ? $"{displayNames[id]} ({id})" : displayNames[id],
                id => id, StringComparer.Ordinal);
            ReplaceOptions(InventoryLocationOptions, new[] { AllLocationsOption }
                .Concat(_locationIdsByLabel.Keys.OrderBy(label => label, StringComparer.OrdinalIgnoreCase)));
            var matchingLabel = _locationIdsByLabel.FirstOrDefault(pair => pair.Value == oldLocationId).Key;
            if (matchingLabel is not null && _selectedComponentLocationFilter != AllLocationsOption)
                _selectedComponentLocationFilter = matchingLabel;
            if (!InventoryLocationOptions.Contains(_selectedComponentLocationFilter))
            {
                _selectedComponentLocationFilter = AllLocationsOption;
                OnPropertyChanged(nameof(SelectedComponentLocationFilter));
            }
            if (!InventorySortOptions.Contains(_selectedComponentSortOption))
            {
                _selectedComponentSortOption = SortUpdatedDescOption;
                OnPropertyChanged(nameof(SelectedComponentSortOption));
            }
        }
        finally
        {
            _updatingOptions = false;
        }
        // The ComboBoxes may clear SelectedItem while their option lists are rebuilt.
        OnPropertyChanged(nameof(SelectedComponentCategoryFilter));
        OnPropertyChanged(nameof(SelectedComponentLocationFilter));
        OnPropertyChanged(nameof(SelectedComponentSortOption));
    }

    private static void ReplaceOptions(ObservableCollection<string> target, IEnumerable<string> options)
    {
        target.Clear();
        foreach (var option in options) target.Add(option);
    }
}



