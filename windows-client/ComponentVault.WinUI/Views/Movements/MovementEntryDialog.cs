using ComponentVault.WinUI.Localization;
using ComponentVault.WinUI.Models;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.UI;

namespace ComponentVault.WinUI.Views.Movements;

/// <summary>Collects a movement draft; persistence and refresh belong to the view models.</summary>
public sealed class MovementEntryDialog(
    XamlRoot xamlRoot,
    IReadOnlyList<ComponentRecord> components,
    IReadOnlyList<StorageLocationRecord> locations)
{
    private sealed record MovementTypeOption(string Value, string Label);

    public async Task<MovementEntryDraft?> ShowAsync()
    {
        var componentCombo = new ComboBox
        {
            ItemsSource = components,
            DisplayMemberPath = nameof(ComponentRecord.Name),
            SelectedIndex = 0,
        };
        var movementTypeCombo = new ComboBox
        {
            ItemsSource = new[]
            {
                new MovementTypeOption("inbound", "入库"),
                new MovementTypeOption("outbound", "出库"),
                new MovementTypeOption("adjustment", "调整"),
                new MovementTypeOption("transfer", "库位调拨"),
            },
            DisplayMemberPath = nameof(MovementTypeOption.Label),
            SelectedIndex = 0,
        };
        var sourceLocationCombo = new ComboBox
        {
            ItemsSource = locations,
            DisplayMemberPath = nameof(StorageLocationRecord.Name),
            PlaceholderText = "选择来源/作业库位",
        };
        var destinationLocationCombo = new ComboBox
        {
            ItemsSource = locations,
            DisplayMemberPath = nameof(StorageLocationRecord.Name),
            PlaceholderText = "调拨目标库位",
        };
        void RefreshSourceLocations()
        {
            var component = componentCombo.SelectedItem as ComponentRecord;
            sourceLocationCombo.SelectedItem = locations.FirstOrDefault(item => item.Id == component?.Location)
                ?? locations.FirstOrDefault();
        }
        componentCombo.SelectionChanged += (_, _) => RefreshSourceLocations();
        RefreshSourceLocations();
        var quantityBox = new NumberBox
        {
            Value = 1,
            Minimum = -100000,
            SmallChange = 1,
            SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact,
        };
        var reasonBox = new TextBox
        {
            PlaceholderText = "例如 到货、样机装配、盘点修正",
        };
        var noteBox = new TextBox
        {
            PlaceholderText = "填写批次、工单、责任人或额外说明",
            AcceptsReturn = true,
            MinHeight = 92,
            TextWrapping = TextWrapping.Wrap,
        };
        var helperText = new TextBlock
        {
            Foreground = new SolidColorBrush(Color.FromArgb(255, 96, 96, 96)),
            Text = "入库和出库请填写正数；库存调整可以填写正数或负数。",
            TextWrapping = TextWrapping.Wrap,
        };
        var errorText = new TextBlock
        {
            Foreground = new SolidColorBrush(Color.FromArgb(255, 176, 0, 32)),
            TextWrapping = TextWrapping.Wrap,
        };

        var panel = new StackPanel
        {
            Spacing = 16,
            Width = 500,
        };
        panel.Children.Add(CreateSectionHeader("记录对象", "先选择元器件，再填写变动类型。"));
        panel.Children.Add(CreateField("元器件", componentCombo));
        panel.Children.Add(CreateField("变动类型", movementTypeCombo));
        panel.Children.Add(CreateField("作业 / 来源库位", sourceLocationCombo));
        panel.Children.Add(CreateField("调拨目标库位", destinationLocationCombo));

        panel.Children.Add(CreateSectionHeader("数量与原因", "数量将直接影响本地库存。"));
        panel.Children.Add(CreateField("数量", quantityBox));
        panel.Children.Add(helperText);
        panel.Children.Add(CreateField("原因", reasonBox));

        panel.Children.Add(CreateSectionHeader("补充说明", "可填写工单号、批次或盘点备注。"));
        panel.Children.Add(CreateField("备注", noteBox));
        panel.Children.Add(errorText);

        MovementEntryDraft? draft = null;
        var dialog = new ContentDialog
        {
            Title = AppStrings.Get("Movements_Dialog_Title"),
            Content = new ScrollViewer
            {
                Content = panel,
                MaxHeight = 600,
            },
            PrimaryButtonText = AppStrings.Get("Common_Save"),
            CloseButtonText = AppStrings.Get("Common_Cancel"),
            DefaultButton = ContentDialogButton.Primary,
            XamlRoot = xamlRoot,
        };
        dialog.PrimaryButtonClick += (_, args) =>
        {
            if (componentCombo.SelectedItem is not ComponentRecord component)
            {
                errorText.Text = "请选择一个元器件。";
                args.Cancel = true;
                return;
            }

            if (movementTypeCombo.SelectedItem is not MovementTypeOption movementType)
            {
                errorText.Text = "请选择变动类型。";
                args.Cancel = true;
                return;
            }

            if (double.IsNaN(quantityBox.Value))
            {
                errorText.Text = "请输入有效数量。";
                args.Cancel = true;
                return;
            }
            if (sourceLocationCombo.SelectedItem is not StorageLocationRecord source)
            {
                errorText.Text = "请选择具体作业库位。";
                args.Cancel = true;
                return;
            }
            var destination = destinationLocationCombo.SelectedItem as StorageLocationRecord;
            if (movementType.Value == "transfer" && destination is null)
            {
                errorText.Text = "调拨时请选择目标库位。";
                args.Cancel = true;
                return;
            }

            draft = new MovementEntryDraft
            {
                ComponentId = component.Id,
                MovementType = movementType.Value,
                Quantity = (int)Math.Round(quantityBox.Value),
                Reason = reasonBox.Text,
                Note = noteBox.Text,
                LocationId = source.Id,
                DestinationLocationId = destination?.Id,
            };
        };

        var result = await dialog.ShowAsync();
        return result == ContentDialogResult.Primary ? draft : null;
    }

    private static FrameworkElement CreateField(string label, FrameworkElement control)
    {
        var panel = new StackPanel { Spacing = 6 };
        panel.Children.Add(new TextBlock { Text = label, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
        panel.Children.Add(control);
        return panel;
    }

    private static FrameworkElement CreateSectionHeader(string title, string description)
    {
        var panel = new StackPanel { Spacing = 2 };
        panel.Children.Add(
            new TextBlock
            {
                Text = title,
                FontSize = 18,
                FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            }
        );
        panel.Children.Add(
            new TextBlock
            {
                Text = description,
                Foreground = new SolidColorBrush(Color.FromArgb(255, 96, 96, 96)),
                TextWrapping = TextWrapping.Wrap,
            }
        );
        return panel;
    }
}
