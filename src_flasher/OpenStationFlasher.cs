using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Management;
using System.Net;
using System.Reflection;
using System.Security.Principal;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;

[assembly: AssemblyTitle("OpenStation Max Installer")]
[assembly: AssemblyDescription("One-Click Installer powered by yasta_flasher engine")]
[assembly: AssemblyProduct("OpenStation Max")]
[assembly: AssemblyVersion("1.6.3.0")]
[assembly: AssemblyFileVersion("1.6.3.0")]

namespace OpenStationFlasher
{
    public class Program
    {
        [STAThread]
        public static void Main(string[] args)
        {
            try
            {
                ServicePointManager.SecurityProtocol = (SecurityProtocolType)3072 | SecurityProtocolType.Tls12;
                if (args != null && args.Length >= 2 && args[0] == "--snap")
                {
                    RenderOptions.ProcessRenderMode = System.Windows.Interop.RenderMode.SoftwareOnly;
                }
                var app = new Application();
                var win = new MainWindow();
                if (args != null && args.Length >= 2 && args[0] == "--snap")
                {
                    string outPath = args[1];
                    win.ShowActivated = false;
                    win.Loaded += (s, e) =>
                    {
                        var timer = new System.Windows.Threading.DispatcherTimer
                        {
                            Interval = TimeSpan.FromMilliseconds(3500)
                        };
                        timer.Tick += (s2, e2) =>
                        {
                            timer.Stop();
                            win.CaptureToPng(outPath);
                            win.Close();
                        };
                        timer.Start();
                    };
                }
                app.Run(win);
            }
            catch (Exception ex)
            {
                MessageBox.Show(
                    "Ошибка запуска установщика:\n" + ex.Message,
                    "OpenStation Max",
                    MessageBoxButton.OK,
                    MessageBoxImage.Error
                );
            }
        }
    }

    public class MainWindow : Window
    {
        private const string FW_VERSION = "v1.6.3";
        private const string INSTALLER_VERSION = "1.3.9";
        private const string GITHUB_LATEST_API = "https://api.github.com/repos/Olegu621/OPENSTATION/releases/latest";

        private bool _isBusy = false;
        private bool _logVisible = false;
        private bool _closing = false;
        private string _selectedRevision = "auto";

        // Paths resolved dynamically (supports both cloned repo & standalone .exe)
        private string _repoRoot = "";
        private string _toolsRoot = "";

        // UI Elements
        private System.Windows.Shapes.Ellipse _statusDot;
        private TextBlock _txtHeroStatus;
        private TextBlock _txtHeroSub;
        private TextBlock _pillDriverVal;
        private Border _pillDriverBorder;
        private TextBlock _pillConnVal;
        private Border _pillConnBorder;
        private TextBlock _pillRomVal;
        private Border _pillRomBorder;
        private Border _revCard0008;
        private Border _revCard00053;
        private Border _revCardAuto;
        private TextBlock _txtProgressStage;
        private TextBlock _txtProgressPct;
        private ProgressBar _progressBar;
        private Border _progressCard;
        private Button _btnMainAction;
        private TextBlock _btnMainText;
        private TextBlock _txtToggleLog;
        private Border _logDrawer;
        private TextBox _txtLog;
        private Border _badgeAdmin;

        private Thread _pollThread;

        public MainWindow()
        {
            Title = "OpenStation USB • Станция Макс";
            Width = 740;
            Height = 610;
            MinWidth = 680;
            MinHeight = 560;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            ResizeMode = ResizeMode.CanMinimize;
            Background = CreateWindowBackground();

            ResolvePaths();
            BuildUi();
            AppendLog("OpenStation USB " + INSTALLER_VERSION + " готов к установке.");
            if (!string.IsNullOrEmpty(_toolsRoot))
                AppendLog("Рабочая директория инструментов: " + _toolsRoot);

            Closing += (s, e) => { if (_isBusy) { e.Cancel = true; MessageBox.Show("Дождитесь завершения текущей операции."); } };
            Closed += (s, e) => { _closing = true; };
            StartBackgroundPolling();
        }

        public void CaptureToPng(string outPath)
        {
            var el = this.Content as FrameworkElement;
            if (el == null) return;
            this.Content = null;
            int w = 730;
            int h = 580;
            el.Width = w;
            el.Height = h;
            el.Measure(new Size(w, h));
            el.Arrange(new Rect(0, 0, w, h));
            el.UpdateLayout();
            var rtb = new System.Windows.Media.Imaging.RenderTargetBitmap(w, h, 96, 96, PixelFormats.Pbgra32);
            rtb.Render(el);
            var enc = new System.Windows.Media.Imaging.PngBitmapEncoder();
            enc.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(rtb));
            using (var fs = File.Create(outPath))
            {
                enc.Save(fs);
            }
        }

        private Brush CreateWindowBackground()
        {
            var g = new LinearGradientBrush();
            g.StartPoint = new Point(0, 0);
            g.EndPoint = new Point(1, 1);
            g.GradientStops.Add(new GradientStop(Color.FromRgb(11, 9, 27), 0.0));
            g.GradientStops.Add(new GradientStop(Color.FromRgb(27, 18, 54), 0.55));
            g.GradientStops.Add(new GradientStop(Color.FromRgb(10, 9, 27), 1.0));
            return g;
        }

        private void ResolvePaths()
        {
            string exeDir = AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\');
            string appData = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "OpenStation"
            );

            string[] localRepoCandidates = new string[]
            {
                exeDir,
                Directory.GetParent(exeDir) != null ? Directory.GetParent(exeDir).FullName : exeDir,
                Directory.GetCurrentDirectory()
            };

            foreach (string c in localRepoCandidates)
            {
                if (File.Exists(Path.Combine(c, "service_flash", "flash_service.py")) &&
                    File.Exists(Path.Combine(c, "service_flash", "update.exe")))
                {
                    _repoRoot = c;
                    _toolsRoot = c;
                    return;
                }
            }

            _repoRoot = exeDir;
            _toolsRoot = appData;
        }

        private bool IsAdministrator()
        {
            try
            {
                using (var id = WindowsIdentity.GetCurrent())
                {
                    return new WindowsPrincipal(id).IsInRole(WindowsBuiltInRole.Administrator);
                }
            }
            catch
            {
                return false;
            }
        }

        private void RestartAsAdmin()
        {
            try
            {
                var exe = Process.GetCurrentProcess().MainModule.FileName;
                Process.Start(new ProcessStartInfo
                {
                    FileName = exe,
                    Verb = "runas",
                    UseShellExecute = true
                });
                Application.Current.Shutdown();
            }
            catch (Exception ex)
            {
                AppendLog("Не удалось перезапустить с правами Администратора: " + ex.Message);
            }
        }

        private void BuildUi()
        {
            var root = new Grid { Background = CreateWindowBackground() };
            root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
            root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

            // 1. Minimal Top Bar
            var topBar = new Border
            {
                Padding = new Thickness(28, 18, 28, 12),
                BorderBrush = new SolidColorBrush(Color.FromArgb(24, 255, 255, 255)),
                BorderThickness = new Thickness(0, 0, 0, 1),
                Background = new SolidColorBrush(Color.FromArgb(90, 8, 12, 22))
            };
            var topGrid = new Grid();
            topGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            topGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var titleStack = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            titleStack.Children.Add(new TextBlock
            {
                Text = "OPENSTATION USB",
                FontSize = 18,
                FontWeight = FontWeights.Bold,
                Foreground = Brushes.White,
                VerticalAlignment = VerticalAlignment.Center
            });

            var verPill = new Border
            {
                Margin = new Thickness(10, 0, 0, 0),
                Padding = new Thickness(10, 3, 10, 3),
                CornerRadius = new CornerRadius(10),
                Background = new SolidColorBrush(Color.FromArgb(28, 255, 255, 255)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(45, 255, 255, 255)),
                BorderThickness = new Thickness(1),
                VerticalAlignment = VerticalAlignment.Center
            };
            verPill.Child = new TextBlock
            {
                Text = "USB " + INSTALLER_VERSION,
                FontSize = 11,
                FontWeight = FontWeights.SemiBold,
                Foreground = new SolidColorBrush(Color.FromRgb(226, 232, 240))
            };
            titleStack.Children.Add(verPill);

            _badgeAdmin = new Border
            {
                Margin = new Thickness(8, 0, 0, 0),
                Padding = new Thickness(9, 3, 9, 3),
                CornerRadius = new CornerRadius(10),
                Background = new SolidColorBrush(IsAdministrator() ? Color.FromArgb(30, 16, 185, 129) : Color.FromArgb(30, 245, 158, 11)),
                BorderBrush = new SolidColorBrush(IsAdministrator() ? Color.FromArgb(90, 16, 185, 129) : Color.FromArgb(90, 245, 158, 11)),
                BorderThickness = new Thickness(1),
                VerticalAlignment = VerticalAlignment.Center,
                Cursor = IsAdministrator() ? Cursors.Arrow : Cursors.Hand
            };
            _badgeAdmin.Child = new TextBlock
            {
                Text = IsAdministrator() ? "Администратор" : "Нужны права Admin",
                FontSize = 10.5,
                FontWeight = FontWeights.SemiBold,
                Foreground = new SolidColorBrush(IsAdministrator() ? Color.FromRgb(110, 231, 183) : Color.FromRgb(253, 224, 71))
            };
            if (!IsAdministrator())
                _badgeAdmin.MouseLeftButtonUp += (s, e) => RestartAsAdmin();
            titleStack.Children.Add(_badgeAdmin);

            Grid.SetColumn(titleStack, 0);
            topGrid.Children.Add(titleStack);

            _txtToggleLog = new TextBlock
            {
                Text = "Показать лог",
                FontSize = 12,
                Foreground = new SolidColorBrush(Color.FromRgb(148, 163, 184)),
                VerticalAlignment = VerticalAlignment.Center,
                Cursor = Cursors.Hand,
                Padding = new Thickness(8, 4, 8, 4)
            };
            _txtToggleLog.MouseLeftButtonUp += (s, e) => ToggleLogDrawer();
            Grid.SetColumn(_txtToggleLog, 1);
            topGrid.Children.Add(_txtToggleLog);

            topBar.Child = topGrid;
            Grid.SetRow(topBar, 0);
            root.Children.Add(topBar);

            // 2. Center Single-Screen Card & Action Area
            var centerStack = new StackPanel
            {
                Margin = new Thickness(28, 22, 28, 14),
                VerticalAlignment = VerticalAlignment.Center
            };

            // Status Spatial Glass Card
            var card = new Border
            {
                CornerRadius = new CornerRadius(18),
                Padding = new Thickness(24, 22, 24, 22),
                Background = new SolidColorBrush(Color.FromArgb(220, 34, 25, 62)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(36, 255, 255, 255)),
                BorderThickness = new Thickness(1)
            };
            var cardStack = new StackPanel();

            var headRow = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            _statusDot = new System.Windows.Shapes.Ellipse
            {
                Width = 12,
                Height = 12,
                Fill = new SolidColorBrush(Color.FromRgb(245, 158, 11)),
                Margin = new Thickness(0, 0, 12, 0),
                VerticalAlignment = VerticalAlignment.Center
            };
            headRow.Children.Add(_statusDot);

            _txtHeroStatus = new TextBlock
            {
                Text = "Проверка подключения станции...",
                FontSize = 20,
                FontWeight = FontWeights.SemiBold,
                Foreground = Brushes.White,
                TextWrapping = TextWrapping.Wrap
            };
            headRow.Children.Add(_txtHeroStatus);
            cardStack.Children.Add(headRow);

            _txtHeroSub = new TextBlock
            {
                Text = "Подключите Станцию Макс по USB в режиме прошивки.",
                FontSize = 13,
                Foreground = new SolidColorBrush(Color.FromRgb(148, 163, 184)),
                Margin = new Thickness(24, 6, 0, 18),
                TextWrapping = TextWrapping.Wrap
            };
            cardStack.Children.Add(_txtHeroSub);

            // 3 Compact Diagnostic Pills
            var pillsGrid = new Grid();
            pillsGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            pillsGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            pillsGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            _pillDriverBorder = CreateDiagPill("Драйвер WorldCup", "Проверка...", out _pillDriverVal);
            _pillDriverBorder.Margin = new Thickness(0, 0, 6, 0);
            Grid.SetColumn(_pillDriverBorder, 0);
            pillsGrid.Children.Add(_pillDriverBorder);

            _pillConnBorder = CreateDiagPill("USB-подключение", "Ожидание...", out _pillConnVal);
            _pillConnBorder.Margin = new Thickness(3, 0, 3, 0);
            Grid.SetColumn(_pillConnBorder, 1);
            pillsGrid.Children.Add(_pillConnBorder);

            _pillRomBorder = CreateDiagPill("Образ прошивки", "Скачается автоматически", out _pillRomVal);
            _pillRomBorder.Margin = new Thickness(6, 0, 0, 0);
            Grid.SetColumn(_pillRomBorder, 2);
            pillsGrid.Children.Add(_pillRomBorder);

            cardStack.Children.Add(pillsGrid);
            card.Child = cardStack;
            centerStack.Children.Add(card);

            // Board Revision Selector Card
            var revBox = new Border
            {
                Margin = new Thickness(0, 12, 0, 0),
                Padding = new Thickness(16, 11, 16, 12),
                CornerRadius = new CornerRadius(14),
                Background = new SolidColorBrush(Color.FromArgb(170, 20, 18, 42)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(30, 255, 255, 255)),
                BorderThickness = new Thickness(1)
            };
            var revStack = new StackPanel();
            revStack.Children.Add(new TextBlock
            {
                Text = "Ревизия платы (см. модель на наклейке снизу станции)",
                FontSize = 11.5,
                FontWeight = FontWeights.SemiBold,
                Foreground = new SolidColorBrush(Color.FromRgb(203, 213, 225)),
                Margin = new Thickness(0, 0, 0, 8)
            });
            var revGrid = new Grid();
            revGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            revGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            revGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            _revCard0008 = CreateRevisionOption("YNDX-0008 / 00052", "Intel Wi-Fi/BT · misc.img", "0008");
            _revCard0008.Margin = new Thickness(0, 0, 5, 0);
            Grid.SetColumn(_revCard0008, 0);
            revGrid.Children.Add(_revCard0008);

            _revCard00053 = CreateRevisionOption("YNDX-00053", "Realtek/Unisoc · misc_00053", "00053");
            _revCard00053.Margin = new Thickness(3, 0, 3, 0);
            Grid.SetColumn(_revCard00053, 1);
            revGrid.Children.Add(_revCard00053);

            _revCardAuto = CreateRevisionOption("Не знаю / Авто", "Не менять misc · автофикс", "auto");
            _revCardAuto.Margin = new Thickness(5, 0, 0, 0);
            Grid.SetColumn(_revCardAuto, 2);
            revGrid.Children.Add(_revCardAuto);

            revStack.Children.Add(revGrid);
            revBox.Child = revStack;
            centerStack.Children.Add(revBox);
            SelectRevision("auto");

            // Progress Area
            _progressCard = new Border
            {
                Margin = new Thickness(0, 14, 0, 0),
                Padding = new Thickness(18, 12, 18, 14),
                CornerRadius = new CornerRadius(14),
                Background = new SolidColorBrush(Color.FromArgb(100, 12, 18, 32)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(26, 255, 255, 255)),
                BorderThickness = new Thickness(1),
                Visibility = Visibility.Collapsed
            };
            var progStack = new StackPanel();
            var progRow = new Grid();
            progRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            progRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            _txtProgressStage = new TextBlock
            {
                Text = "Подготовка...",
                FontSize = 12.5,
                Foreground = new SolidColorBrush(Color.FromRgb(226, 232, 240))
            };
            Grid.SetColumn(_txtProgressStage, 0);
            progRow.Children.Add(_txtProgressStage);

            _txtProgressPct = new TextBlock
            {
                Text = "0%",
                FontSize = 12.5,
                FontWeight = FontWeights.Bold,
                Foreground = Brushes.White
            };
            Grid.SetColumn(_txtProgressPct, 1);
            progRow.Children.Add(_txtProgressPct);

            progStack.Children.Add(progRow);

            _progressBar = new ProgressBar
            {
                Height = 6,
                Margin = new Thickness(0, 8, 0, 0),
                Minimum = 0,
                Maximum = 100,
                Value = 0,
                Foreground = new SolidColorBrush(Color.FromRgb(241, 245, 249)),
                Background = new SolidColorBrush(Color.FromArgb(40, 255, 255, 255)),
                BorderThickness = new Thickness(0)
            };
            progStack.Children.Add(_progressBar);
            _progressCard.Child = progStack;
            centerStack.Children.Add(_progressCard);

            // ONE Primary Action Button
            _btnMainAction = BuildPrimaryButton();
            _btnMainAction.Margin = new Thickness(0, 22, 0, 0);
            centerStack.Children.Add(_btnMainAction);

            var checkPassButton = new Button
            {
                Content = new TextBlock { Text = "Проверить пароль загрузчика", Foreground = new SolidColorBrush(Color.FromRgb(220, 215, 242)), FontSize = 12, FontWeight = FontWeights.SemiBold },
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 8, 0, 0),
                Cursor = Cursors.Hand,
                ToolTip = "Откроется встроенная утилита checkpass; результат показан в её окне."
            };
            var checkTpl = new ControlTemplate(typeof(Button));
            var checkBorder = new FrameworkElementFactory(typeof(Border));
            checkBorder.Name = "checkBorder";
            checkBorder.SetValue(Border.CornerRadiusProperty, new CornerRadius(10));
            checkBorder.SetValue(Border.BackgroundProperty, new SolidColorBrush(Color.FromRgb(36, 30, 65)));
            checkBorder.SetValue(Border.BorderBrushProperty, new SolidColorBrush(Color.FromRgb(85, 71, 128)));
            checkBorder.SetValue(Border.BorderThicknessProperty, new Thickness(1));
            checkBorder.SetValue(Border.PaddingProperty, new Thickness(16, 7, 16, 7));
            checkBorder.AppendChild(new FrameworkElementFactory(typeof(ContentPresenter)));
            checkTpl.VisualTree = checkBorder;
            var checkHover = new Trigger { Property = UIElement.IsMouseOverProperty, Value = true };
            checkHover.Setters.Add(new Setter(Border.BackgroundProperty, new SolidColorBrush(Color.FromRgb(57, 46, 95)), "checkBorder"));
            checkTpl.Triggers.Add(checkHover);
            checkPassButton.Template = checkTpl;
            checkPassButton.Click += OnCheckPassClick;
            centerStack.Children.Add(checkPassButton);

            centerStack.Children.Add(new TextBlock
            {
                Text = "Прошивка OpenStation Max с автоматическим завершением установки.",
                FontSize = 11.5,
                Foreground = new SolidColorBrush(Color.FromRgb(100, 116, 139)),
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 12, 0, 0)
            });

            Grid.SetRow(centerStack, 1);
            root.Children.Add(centerStack);

            // 3. Collapsible Log Drawer
            _logDrawer = new Border
            {
                Margin = new Thickness(28, 0, 28, 18),
                CornerRadius = new CornerRadius(12),
                Background = new SolidColorBrush(Color.FromRgb(4, 7, 13)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(30, 255, 255, 255)),
                BorderThickness = new Thickness(1),
                Padding = new Thickness(10),
                Visibility = Visibility.Collapsed
            };
            _txtLog = new TextBox
            {
                Height = 135,
                IsReadOnly = true,
                TextWrapping = TextWrapping.Wrap,
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                Background = Brushes.Transparent,
                BorderThickness = new Thickness(0),
                Foreground = new SolidColorBrush(Color.FromRgb(203, 213, 225)),
                FontFamily = new FontFamily("Consolas"),
                FontSize = 11.5
            };
            _logDrawer.Child = _txtLog;
            Grid.SetRow(_logDrawer, 2);
            root.Children.Add(_logDrawer);

            Content = root;
        }

        private Border CreateRevisionOption(string title, string sub, string revKey)
        {
            var box = new Border
            {
                CornerRadius = new CornerRadius(10),
                Padding = new Thickness(10, 7, 10, 8),
                Background = new SolidColorBrush(Color.FromArgb(90, 15, 23, 42)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(30, 255, 255, 255)),
                BorderThickness = new Thickness(1),
                Cursor = Cursors.Hand
            };
            var sp = new StackPanel();
            sp.Children.Add(new TextBlock
            {
                Text = title,
                FontSize = 12,
                FontWeight = FontWeights.SemiBold,
                Foreground = Brushes.White
            });
            sp.Children.Add(new TextBlock
            {
                Text = sub,
                FontSize = 10.5,
                Foreground = new SolidColorBrush(Color.FromRgb(148, 163, 184)),
                Margin = new Thickness(0, 2, 0, 0),
                TextTrimming = TextTrimming.CharacterEllipsis
            });
            box.Child = sp;
            box.MouseLeftButtonUp += (s, e) => { if (!_isBusy) SelectRevision(revKey); };
            return box;
        }

        private void SelectRevision(string revKey)
        {
            _selectedRevision = revKey;
            ApplyRevStyle(_revCard0008, revKey == "0008");
            ApplyRevStyle(_revCard00053, revKey == "00053");
            ApplyRevStyle(_revCardAuto, revKey == "auto");
        }

        private static void ApplyRevStyle(Border card, bool selected)
        {
            if (card == null) return;
            card.Background = new SolidColorBrush(selected
                ? Color.FromArgb(165, 49, 46, 129)
                : Color.FromArgb(90, 15, 23, 42));
            card.BorderBrush = new SolidColorBrush(selected
                ? Color.FromRgb(129, 140, 248)
                : Color.FromArgb(30, 255, 255, 255));
            card.BorderThickness = new Thickness(selected ? 1.5 : 1);
        }

        private Border CreateDiagPill(string label, string initVal, out TextBlock valueOut)
        {
            var box = new Border
            {
                CornerRadius = new CornerRadius(12),
                Padding = new Thickness(12, 9, 12, 9),
                Background = new SolidColorBrush(Color.FromArgb(90, 20, 30, 50)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(26, 255, 255, 255)),
                BorderThickness = new Thickness(1)
            };
            var sp = new StackPanel();
            sp.Children.Add(new TextBlock
            {
                Text = label,
                FontSize = 10.5,
                Foreground = new SolidColorBrush(Color.FromRgb(148, 163, 184))
            });
            valueOut = new TextBlock
            {
                Text = initVal,
                FontSize = 12.5,
                FontWeight = FontWeights.SemiBold,
                Foreground = Brushes.White,
                Margin = new Thickness(0, 2, 0, 0),
                TextTrimming = TextTrimming.CharacterEllipsis
            };
            sp.Children.Add(valueOut);
            box.Child = sp;
            return box;
        }

        private Button BuildPrimaryButton()
        {
            _btnMainText = new TextBlock
            {
                Text = "Проверить подключение и установить",
                FontSize = 15,
                FontWeight = FontWeights.Bold,
                Foreground = Brushes.White,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center
            };

            var tpl = new ControlTemplate(typeof(Button));
            var border = new FrameworkElementFactory(typeof(Border));
            border.Name = "bd";
            border.SetValue(Border.CornerRadiusProperty, new CornerRadius(16));
            border.SetValue(Border.BackgroundProperty, new SolidColorBrush(Color.FromRgb(115, 76, 222)));
            border.SetValue(Border.PaddingProperty, new Thickness(24, 15, 24, 15));
            var presenter = new FrameworkElementFactory(typeof(ContentPresenter));
            presenter.SetValue(HorizontalAlignmentProperty, HorizontalAlignment.Center);
            presenter.SetValue(VerticalAlignmentProperty, VerticalAlignment.Center);
            border.AppendChild(presenter);
            tpl.VisualTree = border;

            var hov = new Trigger { Property = UIElement.IsMouseOverProperty, Value = true };
            hov.Setters.Add(new Setter(Border.BackgroundProperty, new SolidColorBrush(Color.FromRgb(139, 99, 245)), "bd"));
            tpl.Triggers.Add(hov);

            var dis = new Trigger { Property = UIElement.IsEnabledProperty, Value = false };
            dis.Setters.Add(new Setter(Border.BackgroundProperty, new SolidColorBrush(Color.FromRgb(71, 85, 105)), "bd"));
            tpl.Triggers.Add(dis);

            var btn = new Button
            {
                Template = tpl,
                Content = _btnMainText,
                Cursor = Cursors.Hand,
                Height = 54
            };
            btn.Click += OnPrimaryActionClick;
            return btn;
        }

        private void ToggleLogDrawer(bool? forceShow = null)
        {
            _logVisible = forceShow.HasValue ? forceShow.Value : !_logVisible;
            _logDrawer.Visibility = _logVisible ? Visibility.Visible : Visibility.Collapsed;
            _txtToggleLog.Text = _logVisible ? "Скрыть лог" : "Показать лог";
            Height = _logVisible ? 760 : 610;
        }

        private void AppendLog(string line)
        {
            if (string.IsNullOrEmpty(line)) return;
            Dispatcher.BeginInvoke(new Action(() =>
            {
                _txtLog.AppendText("[" + DateTime.Now.ToString("HH:mm:ss") + "] " + line + Environment.NewLine);
                _txtLog.ScrollToEnd();
            }));
        }

        private void SetProgress(int pct, string stage)
        {
            Dispatcher.BeginInvoke(new Action(() =>
            {
                _progressCard.Visibility = Visibility.Visible;
                _progressBar.Value = Math.Max(0, Math.Min(100, pct));
                _txtProgressPct.Text = Math.Max(0, Math.Min(100, pct)) + "%";
                if (!string.IsNullOrEmpty(stage))
                    _txtProgressStage.Text = stage;
            }));
        }

        private void StartBackgroundPolling()
        {
            _pollThread = new Thread(() =>
            {
                while (!_closing)
                {
                    if (!_isBusy)
                    {
                        try
                        {
                            var st = ProbeDeviceSnapshot();
                            Dispatcher.BeginInvoke(new Action(() => ApplySnapshotToUi(st)));
                        }
                        catch { }
                    }
                    Thread.Sleep(1500);
                }
            });
            _pollThread.IsBackground = true;
            _pollThread.Start();
        }

        private class DeviceSnapshot
        {
            public bool DriverOk;
            public bool UsbConnected;
            public string UsbDesc = "";
            public bool AdbConnected;
            public string AdbSerial = "";
            public string LocalRomVer = "";
            public string LocalRomDir = "";
        }

        private DeviceSnapshot ProbeDeviceSnapshot()
        {
            var snap = new DeviceSnapshot();
            try
            {
                string sysLibusb = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.Windows),
                    "System32", "drivers", "libusb0.sys"
                );
                snap.DriverOk = File.Exists(sysLibusb);
            }
            catch { }

            try
            {
                using (var searcher = new ManagementObjectSearcher(
                    "SELECT Name, Status, Service, Present FROM Win32_PnPEntity WHERE DeviceID LIKE '%VID_1B8E&PID_C003%' AND Present = TRUE"))
                {
                    foreach (ManagementObject obj in searcher.Get())
                    {
                        snap.UsbConnected = true;
                        string name = Convert.ToString(obj["Name"] ?? "WorldCup Device");
                        string st = Convert.ToString(obj["Status"] ?? "");
                        string svc = Convert.ToString(obj["Service"] ?? "");
                        snap.UsbDesc = (name + " " + st + (string.IsNullOrEmpty(svc) ? "" : " [" + svc + "]")).Trim();
                        if (st.Equals("OK", StringComparison.OrdinalIgnoreCase))
                            snap.DriverOk = true;
                        break;
                    }
                }
            }
            catch { }

            // Fallback driver store check if no live USB plugged yet
            if (!snap.DriverOk)
            {
                try
                {
                    var p = Process.Start(new ProcessStartInfo
                    {
                        FileName = "pnputil",
                        Arguments = "/enum-drivers",
                        UseShellExecute = false,
                        RedirectStandardOutput = true,
                        CreateNoWindow = true
                    });
                    if (p != null)
                    {
                        string o = p.StandardOutput.ReadToEnd().ToLowerInvariant();
                        p.WaitForExit(3000);
                        if (o.Contains("worldcup") || o.Contains("libusb0") || o.Contains("amlogic"))
                            snap.DriverOk = true;
                    }
                }
                catch { }
            }

            // Check ADB if available
            string adbPath = Path.Combine(_toolsRoot, "service_flash", "adb", "adb.exe");
            if (!File.Exists(adbPath))
                adbPath = Path.Combine(_repoRoot, "service_flash", "adb", "adb.exe");
            if (File.Exists(adbPath))
            {
                try
                {
                    var p = Process.Start(new ProcessStartInfo
                    {
                        FileName = adbPath,
                        Arguments = "devices",
                        UseShellExecute = false,
                        RedirectStandardOutput = true,
                        CreateNoWindow = true
                    });
                    if (p != null)
                    {
                        string outStr = p.StandardOutput.ReadToEnd();
                        p.WaitForExit(2500);
                        foreach (string raw in outStr.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries))
                        {
                            string l = raw.Trim();
                            if (l.StartsWith("List of devices")) continue;
                            string[] parts = Regex.Split(l, @"\s+");
                            if (parts.Length >= 2 && parts[1] == "device")
                            {
                                snap.AdbConnected = true;
                                snap.AdbSerial = parts[0];
                                break;
                            }
                        }
                    }
                }
                catch { }
            }

            // Check local / cached ROM
            string appDataRomCache = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "OpenStation", "rom_cache"
            );
            var romDirs = new List<string>
            {
                Path.Combine(_repoRoot, "rom"),
                Path.Combine(_repoRoot, "service_flash", "rom"),
                Path.Combine(_repoRoot, "boot_out"),
                Path.Combine(_toolsRoot, "rom"),
                Path.Combine(_toolsRoot, "rom_cache")
            };
            if (Directory.Exists(appDataRomCache))
            {
                try
                {
                    foreach (string sub in Directory.GetDirectories(appDataRomCache, "rom_v*_verified"))
                        romDirs.Add(sub);
                }
                catch { }
            }
            foreach (string rd in romDirs)
            {
                if (Directory.Exists(rd) &&
                    File.Exists(Path.Combine(rd, "vbmeta.img")) &&
                    File.Exists(Path.Combine(rd, "vendor.img")) &&
                    File.Exists(Path.Combine(rd, "system.img")))
                {
                    string romVer = ReadRomManifestVersion(rd);
                    snap.LocalRomVer = string.IsNullOrEmpty(romVer) ? "Готов (локальный)" : ("Готов (v" + romVer.TrimStart('v', 'V') + ")");
                    if (rd.StartsWith(_repoRoot, StringComparison.OrdinalIgnoreCase))
                        snap.LocalRomDir = rd;
                    break;
                }
            }
            if (string.IsNullOrEmpty(snap.LocalRomVer))
            {
                try
                {
                    string[] zips = Directory.Exists(_repoRoot)
                        ? Directory.GetFiles(_repoRoot, "OpenStationMax-ROM-*.zip")
                        : new string[0];
                    if (zips.Length > 0)
                        snap.LocalRomVer = "Готов (локальный ZIP)";
                }
                catch { }
            }
            if (string.IsNullOrEmpty(snap.LocalRomVer))
                snap.LocalRomVer = "Скачается с GitHub";

            return snap;
        }

        private static string ReadRomManifestVersion(string romDir)
        {
            try
            {
                string mf = Path.Combine(romDir, "rom_manifest.json");
                if (!File.Exists(mf)) return "";
                string txt = File.ReadAllText(mf, Encoding.UTF8);
                var m = Regex.Match(txt, "\"version\"\\s*:\\s*\"([^\"]+)\"");
                return m.Success ? m.Groups[1].Value.Trim() : "";
            }
            catch
            {
                return "";
            }
        }

        private void ApplySnapshotToUi(DeviceSnapshot s)
        {
            if (_isBusy) return;

            _pillDriverVal.Text = s.DriverOk ? "Установлен" : "Нужна установка";
            _pillDriverBorder.BorderBrush = new SolidColorBrush(s.DriverOk
                ? Color.FromArgb(90, 16, 185, 129)
                : Color.FromArgb(90, 245, 158, 11));

            if (s.UsbConnected)
            {
                _statusDot.Fill = new SolidColorBrush(Color.FromRgb(52, 211, 153));
                _txtHeroStatus.Text = "Обнаружено устройство Amlogic";
                _txtHeroSub.Text = "Убедитесь, что подключена именно Станция Макс. Модель по USB определить невозможно.";
                _pillConnVal.Text = "USB Boot OK";
                _pillConnBorder.BorderBrush = new SolidColorBrush(Color.FromArgb(90, 16, 185, 129));
            }
            else if (s.AdbConnected)
            {
                _statusDot.Fill = new SolidColorBrush(Color.FromRgb(245, 158, 11));
                _txtHeroStatus.Text = "Нужно подключение по сервисному USB";
                _txtHeroSub.Text = "Подключите станцию к ПК по USB в режиме прошивки.";
                _pillConnVal.Text = "Нужен USB";
                _pillConnBorder.BorderBrush = new SolidColorBrush(Color.FromArgb(90, 245, 158, 11));
            }
            else
            {
                _statusDot.Fill = new SolidColorBrush(Color.FromRgb(245, 158, 11));
                _txtHeroStatus.Text = "Ожидание подключения станции";
                _txtHeroSub.Text = "Подключите Станцию Макс в том же режиме, в котором её видит USB Burning Tool.";
                _pillConnVal.Text = "Не подключена";
                _pillConnBorder.BorderBrush = new SolidColorBrush(Color.FromArgb(40, 255, 255, 255));
            }

            _pillRomVal.Text = s.LocalRomVer;
        }

        private async void OnCheckPassClick(object sender, RoutedEventArgs e)
        {
            if (_isBusy) return;
            _isBusy = true;
            _btnMainAction.IsEnabled = false;
            ((Button)sender).IsEnabled = false;
            try
            {
                string folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "OpenStation");
                Directory.CreateDirectory(folder);
                string exe = Path.Combine(folder, "checkpass.exe");
                using (Stream source = Assembly.GetExecutingAssembly().GetManifestResourceStream("CheckPass"))
                {
                    if (source == null) throw new Exception("Утилита checkpass не встроена в установщик.");
                    using (var dest = new FileStream(exe, FileMode.Create, FileAccess.Write, FileShare.None)) source.CopyTo(dest);
                }
                AppendLog("Открыта проверка загрузчика. Результат смотрите в окне checkpass.");
                await Task.Run(() => {
                    using (var process = Process.Start(new ProcessStartInfo { FileName = exe, WorkingDirectory = folder, UseShellExecute = true }))
                    {
                        if (process == null) throw new Exception("Не удалось открыть checkpass.");
                        process.WaitForExit();
                    }
                });
                AppendLog("Проверка закрыта. Установщик не определяет результат автоматически.");
            }
            catch (Exception ex) { AppendLog("Не удалось запустить checkpass: " + ex.Message); ToggleLogDrawer(true); }
            finally { _isBusy = false; _btnMainAction.IsEnabled = true; ((Button)sender).IsEnabled = true; }
        }

        private async void OnPrimaryActionClick(object sender, RoutedEventArgs e)
        {
            if (_isBusy) return;

            var snap = ProbeDeviceSnapshot();
            ApplySnapshotToUi(snap);

            if (!snap.DriverOk && !IsAdministrator())
            {
                var r = MessageBox.Show(
                    "Для установки драйвера WorldCup требуются права Администратора. Перезапустить от имени Администратора?",
                    "OpenStation Max",
                    MessageBoxButton.YesNo,
                    MessageBoxImage.Question
                );
                if (r == MessageBoxResult.Yes) RestartAsAdmin();
                return;
            }

            _isBusy = true;
            _btnMainAction.IsEnabled = false;
            _btnMainText.Text = "Выполняется проверка и установка...";
            _statusDot.Fill = new SolidColorBrush(Color.FromRgb(56, 189, 248));

            try
            {
                await Task.Run(() => RunFullInstallWorkflow(snap));
            }
            catch (Exception ex)
            {
                AppendLog("ОШИБКА: " + ex.Message);
                Dispatcher.Invoke(() =>
                {
                    _statusDot.Fill = new SolidColorBrush(Color.FromRgb(248, 113, 113));
                    _txtHeroStatus.Text = "Установка прервана";
                    _txtHeroSub.Text = ex.Message;
                    ToggleLogDrawer(true);
                });
            }
            finally
            {
                _isBusy = false;
                Dispatcher.Invoke(() =>
                {
                    _btnMainAction.IsEnabled = true;
                    _btnMainText.Text = "Проверить подключение и установить";
                });
            }
        }

        private static bool IsVersionAtLeast(string actual, string required)
        {
            try
            {
                Version a = new Version(actual.Trim().TrimStart('v', 'V'));
                Version b = new Version(required.Trim().TrimStart('v', 'V'));
                return a >= b;
            }
            catch
            {
                return false;
            }
        }

        private void EnsureFlashToolsPresent()
        {
            string appData = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "OpenStation"
            );

            string flashPy = Path.Combine(_toolsRoot, "service_flash", "flash_service.py");
            string updateExe = Path.Combine(_toolsRoot, "service_flash", "update.exe");
            string verFile = Path.Combine(_toolsRoot, "service_flash", "tools_version.txt");

            bool isAppDataCache = string.Equals(
                Path.GetFullPath(_toolsRoot).TrimEnd('\\'),
                Path.GetFullPath(appData).TrimEnd('\\'),
                StringComparison.OrdinalIgnoreCase
            );

            string localVer = null;
            if (File.Exists(verFile))
            {
                try { localVer = File.ReadAllText(verFile).Trim(); } catch { }
            }
            bool versionMatches = string.Equals(localVer, FW_VERSION, StringComparison.OrdinalIgnoreCase);
            bool versionFresh = versionMatches || IsVersionAtLeast(localVer ?? "", FW_VERSION);

            // An old portable copy (e.g. a GitHub ZIP extracted next to the .exe)
            // must not silently override the tools that ship with a newer
            // installer: otherwise a fresh .exe runs stale python/dll files.
            if (!isAppDataCache && File.Exists(flashPy) && File.Exists(updateExe) && !versionFresh)
            {
                AppendLog("Локальные утилиты (" + (string.IsNullOrEmpty(localVer) ? "версия неизвестна" : localVer) +
                          ") устарели — переключаюсь на актуальный набор " + FW_VERSION + ".");
                _toolsRoot = appData;
                isAppDataCache = true;
                flashPy = Path.Combine(_toolsRoot, "service_flash", "flash_service.py");
                updateExe = Path.Combine(_toolsRoot, "service_flash", "update.exe");
                verFile = Path.Combine(_toolsRoot, "service_flash", "tools_version.txt");
                versionMatches = false;
                if (File.Exists(verFile))
                {
                    try { versionMatches = string.Equals(File.ReadAllText(verFile).Trim(), FW_VERSION, StringComparison.OrdinalIgnoreCase); } catch { }
                }
            }

            if (File.Exists(flashPy) && File.Exists(updateExe) && (!isAppDataCache || versionMatches))
                return;

            SetProgress(4, "Обновление утилит прошивки (" + FW_VERSION + ")...");
            AppendLog("Загрузка актуального набора утилит прошивки (" + FW_VERSION + ") с GitHub Releases...");

            string json = HttpGetString(GITHUB_LATEST_API);
            var m = Regex.Match(json, "\"browser_download_url\"\\s*:\\s*\"([^\"]*OpenStationMax-FlasherTools[^\"]*\\.zip)\"", RegexOptions.IgnoreCase);
            if (!m.Success)
                throw new Exception("Не найден пакет инструментов OpenStationMax-FlasherTools.zip в релизе GitHub. Скачайте полный релиз или клонируйте репозиторий.");

            string url = m.Groups[1].Value;
            Directory.CreateDirectory(_toolsRoot);
            string zipPath = Path.Combine(_toolsRoot, "tools.zip");
            DownloadFile(url, zipPath);

            AppendLog("Распаковка инструментов в " + _toolsRoot + "...");
            using (var archive = ZipFile.OpenRead(zipPath))
            {
                foreach (var entry in archive.Entries)
                {
                    if (string.IsNullOrEmpty(entry.Name)) continue;
                    string dest = Path.Combine(_toolsRoot, entry.FullName.Replace('/', '\\'));
                    Directory.CreateDirectory(Path.GetDirectoryName(dest));
                    entry.ExtractToFile(dest, true);
                }
            }
            try { File.WriteAllText(verFile, FW_VERSION + "\n", Encoding.UTF8); } catch { }
            try { File.Delete(zipPath); } catch { }
        }

        private string FindPythonExe()
        {
            string[] cands = new string[] { "python", "py" };
            foreach (string c in cands)
            {
                try
                {
                    string args = (c == "py") ? "-3 --version" : "--version";
                    var p = Process.Start(new ProcessStartInfo
                    {
                        FileName = c,
                        Arguments = args,
                        UseShellExecute = false,
                        RedirectStandardOutput = true,
                        RedirectStandardError = true,
                        CreateNoWindow = true
                    });
                    if (p != null)
                    {
                        p.WaitForExit(3000);
                        if (p.ExitCode == 0) return c;
                    }
                }
                catch { }
            }
            return "";
        }

        private void RunFullInstallWorkflow(DeviceSnapshot snap)
        {
            if (!snap.UsbConnected)
                throw new Exception("Подключите Станцию Макс по сервисному USB и повторите попытку.");
            string selectedRev = "auto";
            Dispatcher.Invoke(() => selectedRev = _selectedRevision);
            string revSummary = selectedRev == "0008"
                ? "YNDX-0008 / 00052 (Intel -> запись misc.img)"
                : (selectedRev == "00053"
                    ? "YNDX-00053 (Realtek/Unisoc -> запись misc_00053.img)"
                    : "Не выбрана / Авто (раздел misc не перезаписывается)");
            bool proceed = false;
            Dispatcher.Invoke(() =>
            {
                proceed = MessageBox.Show(
                    "Установка OpenStation Max\n\n" +
                    "Ревизия: " + revSummary + "\n" +
                    "Будут перезаписаны системные разделы. Подключайте только Станцию Макс; " +
                    "убедитесь, что доступен проверенный способ восстановления. Продолжить?",
                    "Установка OpenStation", MessageBoxButton.YesNo,
                    MessageBoxImage.Warning) == MessageBoxResult.Yes;
            });
            if (!proceed) { AppendLog("Установка отменена."); return; }
            string folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                                         "OpenStation", "installer");
            Directory.CreateDirectory(folder);
            string engine = Path.Combine(folder, "OpenStationEngine.exe");
            using (Stream source = Assembly.GetExecutingAssembly().GetManifestResourceStream("OpenStationEngine"))
            {
                if (source == null) throw new Exception("Внутренний модуль прошивки отсутствует.");
                using (var dest = new FileStream(engine, FileMode.Create, FileAccess.Write, FileShare.None))
                    source.CopyTo(dest);
            }
            AppendLog("Запуск встроенного модуля прошивки (" + revSummary + "). Не отключайте питание и кабель.");
            string engineArgs = "--flash --revision " + selectedRev;
            if (!string.IsNullOrEmpty(snap.LocalRomDir) && Directory.Exists(snap.LocalRomDir))
            {
                string mfVer = ReadRomManifestVersion(snap.LocalRomDir);
                if (string.IsNullOrEmpty(mfVer) || IsVersionAtLeast(mfVer, FW_VERSION))
                {
                    AppendLog("Используется локальный образ ROM: " + snap.LocalRomDir);
                    engineArgs = "--flash --revision " + selectedRev + " --rom-dir \"" + snap.LocalRomDir.Replace("\"", "\\\"") + "\"";
                }
                else
                {
                    AppendLog("Локальный ROM (" + mfVer + ") старше " + FW_VERSION + " — загружаем актуальный с GitHub.");
                }
            }
            int rc = RunPythonStream(engine, engineArgs);
            if (rc != 0 && rc != 3) throw new Exception("Операция остановлена (код " + rc + "). Сохраните журнал.");
            bool unconfirmed = rc == 3;
            SetProgress(100, unconfirmed ? "Запись разделов завершена" : "Запись и завершающие команды выполнены");
            Dispatcher.Invoke(() => {
                _statusDot.Fill = new SolidColorBrush(Color.FromRgb(52, 211, 153));
                _txtHeroStatus.Text = "Запись завершена";
                _txtHeroSub.Text = unconfirmed ? "Запись разделов завершена. Загрузка станции ещё не проверена." : "Завершающие команды выполнены. Перезапустите питание станции.";
            });
        }

        private int RunPythonStream(string exe, string args)
        {
            var psi = new ProcessStartInfo
            {
                FileName = exe,
                Arguments = args,
                WorkingDirectory = Path.GetDirectoryName(exe),
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8
            };

            using (var p = Process.Start(psi))
            {
                int currentPct = 0;
                p.OutputDataReceived += (s, ev) =>
                {
                    if (ev.Data == null) return;
                    string ln = ev.Data.Trim();
                    if (ln.StartsWith("[PCT] "))
                    {
                        int parsed;
                        if (int.TryParse(ln.Substring(6).Trim(), out parsed))
                        {
                            currentPct = parsed;
                            SetProgress(currentPct, "");
                        }
                    }
                    else if (ln.StartsWith("[STAGE] "))
                    {
                        SetProgress(currentPct, ln.Substring(8).Trim());
                        AppendLog(ln.Substring(8).Trim());
                    }
                    else
                    {
                        AppendLog(ln);
                    }
                };
                p.ErrorDataReceived += (s, ev) =>
                {
                    if (!string.IsNullOrEmpty(ev.Data))
                        AppendLog("[STDERR] " + ev.Data);
                };
                p.BeginOutputReadLine();
                p.BeginErrorReadLine();
                p.WaitForExit();
                return p.ExitCode;
            }
        }

        private string HttpGetString(string url)
        {
            var req = (HttpWebRequest)WebRequest.Create(url);
            req.UserAgent = "OpenStationFlasher/1.6";
            req.Timeout = 20000;
            using (var resp = (HttpWebResponse)req.GetResponse())
            using (var sr = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
            {
                return sr.ReadToEnd();
            }
        }

        private void DownloadFile(string url, string dest)
        {
            var req = (HttpWebRequest)WebRequest.Create(url);
            req.UserAgent = "OpenStationFlasher/1.6";
            req.Timeout = 60000;
            using (var resp = (HttpWebResponse)req.GetResponse())
            using (var s = resp.GetResponseStream())
            using (var fs = File.Create(dest))
            {
                byte[] buf = new byte[65536];
                int n;
                while ((n = s.Read(buf, 0, buf.Length)) > 0)
                    fs.Write(buf, 0, n);
            }
        }
    }
}
