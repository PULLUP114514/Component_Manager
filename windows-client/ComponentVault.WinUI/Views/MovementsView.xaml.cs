using ComponentVault.WinUI.Design;
using ComponentVault.WinUI.Localization;
using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.ViewModels;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using ComponentVault.WinUI.Views.Movements;

namespace ComponentVault.WinUI.Views;

public sealed partial class MovementsView : Page
{
    private MainViewModel? RuntimeViewModel => ViewModelResolver.GetRuntimeViewModel(DataContext);

    public MovementsView()
    {
        InitializeComponent();
        DataContext = ViewModelResolver.ResolveMainViewModel();
    }

    private void OnRefreshFeedClicked(object sender, RoutedEventArgs e)
    {
        RuntimeViewModel?.Refresh();
    }

    private async void OnRecordMovementClicked(object sender, RoutedEventArgs e)
    {
        var viewModel = RuntimeViewModel;
        if (viewModel is null || viewModel.IsBusy)
        {
            return;
        }

        if (viewModel.AvailableComponents.Count == 0)
        {
            await ShowMessageAsync(
                AppStrings.Get("Movements_Dialog_Title"),
                AppStrings.Get("Movements_Dialog_NoComponentMessage")
            );
            return;
        }

        var draft = await new MovementEntryDialog(
            XamlRoot, viewModel.AvailableComponents, viewModel.StorageLocations.ToArray()).ShowAsync();
        if (draft is null)
        {
            return;
        }

        var result = viewModel.RecordMovement(draft);
        await ShowMessageAsync(
            result.IsSuccess
                ? AppStrings.Get("Movements_Dialog_SuccessTitle")
                : AppStrings.Get("Movements_Dialog_FailureTitle"),
            result.Message
        );
    }

    private void OnMovementSelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (RuntimeViewModel is { } viewModel)
        {
            viewModel.SelectedMovement = MovementsListView.SelectedItem as StockMovementRecord;
        }
    }

    private async Task ShowMessageAsync(string title, string message)
    {
        var dialog = new ContentDialog
        {
            Title = title,
            Content = message,
            CloseButtonText = AppStrings.Get("Common_Close"),
            XamlRoot = XamlRoot,
        };
        await dialog.ShowAsync();
    }

}
