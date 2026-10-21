using System.Security.Cryptography;
using System.Text.Json;
using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services.Bom;
using ComponentVault.WinUI.ViewModels;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace ComponentVault.WinUI.Views;

public sealed partial class KittingView : Page
{
    private readonly BomPresetStore _presets = new();
    private readonly BomPlanningService _planner = new();
    private readonly List<BomSourceRow> _rows = [];
    private readonly Dictionary<int, string> _selected = [];
    private readonly HashSet<int> _skipped = [];
    private BomMatchCandidate? _choice;
    private BomPreview? _preview;
    private string _releaseId = Guid.NewGuid().ToString("N");
    private string _batchId = Guid.NewGuid().ToString("N");
    private bool _confirmed;
    private bool _loading;
    private MainViewModel Model => ((App)Application.Current).MainViewModel!;

    public KittingView()
    {
        InitializeComponent();
        NavigationCacheMode = Microsoft.UI.Xaml.Navigation.NavigationCacheMode.Required;
        Loaded += async (_, _) =>
        {
            try { PresetBox.ItemsSource = _presets.LoadAll(); Refresh(); }
            catch (Exception error) { await Message("预设读取失败", error.Message); }
        };
    }

    private async void OnLoadPreset(object sender, RoutedEventArgs e)
    {
        if (PresetBox.SelectedItem is not BomPreset selectedPreset) return;
        try
        {
            var preset = _presets.LoadAll().First(item => item.Id == selectedPreset.Id);
            if (!await ConfirmReplaceDraft()) return;
            _loading = true;
            _rows.Clear(); _rows.AddRange(preset.Document.Rows.Where(row => !preset.SkippedRows.Contains(row.RowNumber)));
            _selected.Clear(); foreach (var pair in preset.SelectedComponents) _selected[pair.Key] = pair.Value;
            _skipped.Clear();
            ProjectBox.Text = preset.ProjectName; BatchBox.Value = preset.BatchQuantity;
            ResetIdentity();
            _loading = false; RebuildRows(); Refresh();
        }
        catch (Exception error) { _loading = false; await Message("预设带入失败", error.Message); }
    }

    private async void OnNewKit(object sender, RoutedEventArgs e)
    {
        if (!await ConfirmReplaceDraft()) return;
        _loading = true; _rows.Clear(); _selected.Clear(); _skipped.Clear();
        ProjectBox.Text = "配套件"; BatchBox.Value = 1; ResetIdentity();
        _loading = false; RebuildRows(); Refresh();
    }
    private async Task<bool> ConfirmReplaceDraft()
    {
        if (_confirmed || _rows.Count == 0) return true;
        var dialog = new ContentDialog
        {
            Title = "替换当前配套件清单？",
            Content = "当前尚未确认出库，继续将清空本次选料和数量设置。库存尚未扣减。",
            PrimaryButtonText = "替换清单", CloseButtonText = "保留当前清单",
            DefaultButton = ContentDialogButton.Close, XamlRoot = XamlRoot,
        };
        return await dialog.ShowAsync() == ContentDialogResult.Primary;
    }
    private void ResetIdentity() { _releaseId = Guid.NewGuid().ToString("N"); _batchId = Guid.NewGuid().ToString("N"); _confirmed = false; }
    private void OnSearchChanged(AutoSuggestBox sender, AutoSuggestBoxTextChangedEventArgs args)
    {
        if (args.Reason != AutoSuggestionBoxTextChangeReason.UserInput) return;
        _choice = null; sender.ItemsSource = _planner.SearchInventory(Model.AvailableComponents, sender.Text);
    }
    private void OnSuggestionChosen(AutoSuggestBox sender, AutoSuggestBoxSuggestionChosenEventArgs args) => _choice = args.SelectedItem as BomMatchCandidate;
    private async void OnAdd(object sender, RoutedEventArgs e)
    {
        if (_confirmed) return;
        if (_choice is null || !PositiveInt(AddQuantity.Value)) { await Message("无法添加", "请选择库存项并填写正整数用量。"); return; }
        var row = _rows.Count == 0 ? 1 : _rows.Max(item => item.RowNumber) + 1;
        _rows.Add(new(row, _choice.Sku, _choice.Model, _choice.PackageName, (int)AddQuantity.Value, _choice.Name));
        _selected[row] = _choice.ComponentId;
        RebuildRows(); Refresh();
    }
    private static bool PositiveInt(double value) => double.IsFinite(value) && value >= 1 && value <= int.MaxValue && value == Math.Truncate(value);
    private void OnProjectChanged(object sender, TextChangedEventArgs e) { if (!_loading && RowsPanel is not null) Refresh(); }
    private void OnBatchChanged(NumberBox sender, NumberBoxValueChangedEventArgs args) { if (!_loading && RowsPanel is not null) Refresh(); }
    private BomDocument Document() => new("配套件", null, _rows.ToArray(), []);

    private void RebuildRows()
    {
        RowsPanel.Children.Clear();
        foreach (var source in _rows.ToArray())
        {
            var panel = new StackPanel { Spacing = 8 };
            panel.Children.Add(new TextBlock { Text = $"第 {source.RowNumber} 行 · {string.Join(" · ", new[] { source.Sku, source.SupplierPartNumber, source.PackageName, source.Name, source.Reference }.Where(value => !string.IsNullOrWhiteSpace(value)))}", TextWrapping = TextWrapping.Wrap });
            var quantity = new NumberBox { Header = "每套用量", Value = source.Quantity ?? double.NaN, Minimum = 1, SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Inline, IsEnabled = !_confirmed };
            quantity.ValueChanged += (_, _) => { var index = _rows.FindIndex(row => row.RowNumber == source.RowNumber); if (index >= 0) _rows[index] = _rows[index] with { Quantity = PositiveInt(quantity.Value) ? (int)quantity.Value : null }; Refresh(); };
            panel.Children.Add(quantity);
            var skip = new CheckBox { Content = "跳过此行", IsChecked = _skipped.Contains(source.RowNumber), IsEnabled = !_confirmed };
            skip.Checked += (_, _) => { _skipped.Add(source.RowNumber); Refresh(); };
            skip.Unchecked += (_, _) => { _skipped.Remove(source.RowNumber); Refresh(); };
            panel.Children.Add(skip);
            var resolve = new Button { Content = "重新选择库存匹配", IsEnabled = !_confirmed };
            resolve.Click += async (_, _) => await Resolve(source.RowNumber);
            panel.Children.Add(resolve);
            RowsPanel.Children.Add(panel);
        }
    }
    private async Task Resolve(int row)
    {
        BomMatchCandidate? selected = null;
        var search = new AutoSuggestBox { Header = "选择库存项", PlaceholderText = "SKU、型号、封装或名称", MinWidth = 360 };
        search.TextChanged += (_, args) => { if (args.Reason == AutoSuggestionBoxTextChangeReason.UserInput) { selected = null; search.ItemsSource = _planner.SearchInventory(Model.AvailableComponents, search.Text); } };
        search.SuggestionChosen += (_, args) => selected = args.SelectedItem as BomMatchCandidate;
        var dialog = new ContentDialog { Title = $"第 {row} 行匹配", Content = search, PrimaryButtonText = "应用", CloseButtonText = "取消", XamlRoot = XamlRoot };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        if (selected is null) { await Message("尚未选择库存", "请从建议中选择库存项。"); return; }
        var group = _preview?.SelectionGroups?.Values.FirstOrDefault(items => items.Contains(row)) ?? [row];
        foreach (var number in group) _selected[number] = selected.ComponentId;
        Refresh();
    }
    private string AllocationPlan(BomConsumptionLine line)
    {
        var component = Model.AvailableComponents.FirstOrDefault(item => item.Id == line.ComponentId);
        if (component is null) return "库存项不可用";
        var remaining = line.RequiredQuantity;
        var parts = new List<string>();
        foreach (var allocation in component.Allocations.Where(item => item.Quantity > 0).OrderBy(item => item.LocationId, StringComparer.Ordinal))
        {
            var take = Math.Min(remaining, allocation.Quantity);
            if (take > 0) parts.Add($"{allocation.LocationName ?? allocation.LocationId} × {take}");
            remaining -= take; if (remaining == 0) break;
        }
        return string.Join("，", parts) + (remaining > 0 ? $"；缺少 {remaining}" : "");
    }
    private void Refresh()
    {
        if (_loading || StatusText is null) return;
        _preview = Model.CreateBomPreview(Document(), ProjectBox.Text, PositiveInt(BatchBox.Value) ? (int)BatchBox.Value : 0, _selected, _skipped);
        StatusText.Text = _confirmed ? "此批次已出库。新建配套件后可开始下一批。" : _preview.Issues.Count > 0 ? string.Join("\n", _preview.Issues.Select(issue => $"{(issue.RowNumber is int row ? $"第 {row} 行：" : "")}{issue.Message}")) : $"{_preview.Lines.Count} 个库存项；已跳过 {_skipped.Count} 行。请核对以下数量及库位。";
        PlanText.Text = _preview.Lines.Count == 0 ? "尚无可出库项目。" : string.Join("\n", _preview.Lines.Select(line => $"{line.ComponentSku} · 每套 {line.UnitQuantity} × {_preview.BatchQuantity} 套 = {line.RequiredQuantity}；现有 {line.AvailableQuantity}\n库位：{AllocationPlan(line)}"));
        ConfirmButton.IsEnabled = !_confirmed && _preview.CanConfirm;
        ProjectBox.IsEnabled = BatchBox.IsEnabled = InventorySearch.IsEnabled = AddQuantity.IsEnabled = !_confirmed;
    }
    private async void OnConfirm(object sender, RoutedEventArgs e)
    {
        Refresh();
        if (_confirmed || _preview is not { CanConfirm: true } preview) return;
        var plan = PlanText.Text;
        var dialog = new ContentDialog { Title = "确认配套件出库", Content = new ScrollViewer { Content = new TextBlock { Text = $"{preview.ProjectName} · {preview.BatchQuantity} 套\n{plan}\n确认后一次扣减。", TextWrapping = TextWrapping.Wrap }, MaxHeight = 420 }, PrimaryButtonText = "确认出库", CloseButtonText = "返回复核", DefaultButton = ContentDialogButton.Close, XamlRoot = XamlRoot };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        var hash = Convert.ToHexString(SHA256.HashData(JsonSerializer.SerializeToUtf8Bytes(Document())));
        var result = Model.ConfirmBomConsumption(new(_releaseId, _batchId, preview.ProjectName, preview.BatchQuantity, hash, preview.Lines));
        if (result.IsSuccess) _confirmed = true;
        RebuildRows(); Refresh();
        await Message(result.IsSuccess ? "配套件出库完成" : "配套件出库失败", result.Message);
    }
    private async Task Message(string title, string text) => await new ContentDialog { Title = title, Content = text, CloseButtonText = "关闭", XamlRoot = XamlRoot }.ShowAsync();
}
