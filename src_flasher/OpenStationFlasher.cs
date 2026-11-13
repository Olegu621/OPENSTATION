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
[assembly: AssemblyVersion("1.5.1.0")]
[assembly: AssemblyFileVersion("1.5.1.0")]

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
        private const string FW_VERSION = "v1.5.1";
        private const string GITHUB_LATEST_API = "https://api.github.com/repos/Olegu621/OPENSTATION/releases/latest";

        private bool _isBusy = false;
        private bool _logVisible = false;
        private bool _closing = false;

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
            Title = "OpenStation Max " + FW_VERSION + " — Установщик";
            Width = 740;
            Height = 520;
            MinWidth = 680;
            MinHeight = 470;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            ResizeMode = ResizeMode.CanMinimize;
            Background = CreateWindowBackground();

            ResolvePaths();
            BuildUi();
            AppendLog("Установщик OpenStation Max " + FW_VERSION + " (движок yasta_flasher) готов.");
            if (!string.IsNullOrEmpty(_toolsRoot))
                AppendLog("Рабочая директория инструментов: " + _toolsRoot);

            Closed += (s, e) => { _closing = true; };
            StartBackgroundPolling();
        }

        public void CaptureToPng(string outPath)
        {
            var el = this.Content as FrameworkElement;
            if (el == null) return;
            this.Content = null;
            int w = 730;
            int h = 490;
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
            g.GradientStops.Add(new GradientStop(Color.FromRgb(4, 7, 14), 0.0));
            g.GradientStops.Add(new GradientStop(Color.FromRgb(9, 15, 28), 0.55));
            g.GradientStops.Add(new GradientStop(Color.FromRgb(5, 9, 18), 1.0));
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
                Text = "OPENSTATION MAX",
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
                Text = FW_VERSION,
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
                Background = new SolidColorBrush(Color.FromArgb(140, 14, 21, 36)),
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
                Text = "Подключите станцию сервисным USB Type-C (перемычка на 6-й контакт) или по ADB.",
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

            _pillConnBorder = CreateDiagPill("Порт / ADB", "Ожидание...", out _pillConnVal);
            _pillConnBorder.Margin = new Thickness(3, 0, 3, 0);
            Grid.SetColumn(_pillConnBorder, 1);
            pillsGrid.Children.Add(_pillConnBorder);

            _pillRomBorder = CreateDiagPill("Образ прошивки", FW_VERSION, out _pillRomVal);
            _pillRomBorder.Margin = new Thickness(6, 0, 0, 0);
            Grid.SetColumn(_pillRomBorder, 2);
            pillsGrid.Children.Add(_pillRomBorder);

            cardStack.Children.Add(pillsGrid);
            card.Child = cardStack;
            centerStack.Children.Add(card);

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

            centerStack.Children.Add(new TextBlock
            {
                Text = "Автоматически проверит драйвер, скачает свежую прошивку с GitHub и прошьёт через yasta_flasher.",
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
                Foreground = new SolidColorBrush(Color.FromRgb(8, 12, 22)),
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center
            };

            var tpl = new ControlTemplate(typeof(Button));
            var border = new FrameworkElementFactory(typeof(Border));
            border.Name = "bd";
            border.SetValue(Border.CornerRadiusProperty, new CornerRadius(16));
            border.SetValue(Border.BackgroundProperty, new SolidColorBrush(Color.FromRgb(241, 245, 249)));
            border.SetValue(Border.PaddingProperty, new Thickness(24, 15, 24, 15));
            var presenter = new FrameworkElementFactory(typeof(ContentPresenter));
            presenter.SetValue(HorizontalAlignmentProperty, HorizontalAlignment.Center);
            presenter.SetValue(VerticalAlignmentProperty, VerticalAlignment.Center);
            border.AppendChild(presenter);
            tpl.VisualTree = border;

            var hov = new Trigger { Property = UIElement.IsMouseOverProperty, Value = true };
            hov.Setters.Add(new Setter(Border.BackgroundProperty, Brushes.White, "bd"));
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
            Height = _logVisible ? 670 : 520;
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
                    "SELECT Name, Status, Service FROM Win32_PnPEntity WHERE DeviceID LIKE '%VID_1B8E&PID_C003%'"))
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

            // Check local ROM
            string[] romDirs = new string[]
            {
                Path.Combine(_repoRoot, "rom"),
                Path.Combine(_toolsRoot, "rom"),
                Path.Combine(_toolsRoot, "rom_cache")
            };
            foreach (string rd in romDirs)
            {
                if (Directory.Exists(rd) &&
                    File.Exists(Path.Combine(rd, "vbmeta.img")) &&
                    File.Exists(Path.Combine(rd, "vendor.img")) &&
                    File.Exists(Path.Combine(rd, "system.img")))
                {
                    snap.LocalRomVer = "Готов (" + FW_VERSION + ")";
                    break;
                }
            }
            if (string.IsNullOrEmpty(snap.LocalRomVer))
                snap.LocalRomVer = "Скачается с GitHub";

            return snap;
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
                _txtHeroStatus.Text = "Станция подключена (USB Boot)";
                _txtHeroSub.Text = "Обнаружено устройство Amlogic 1B8E:C003. Нажмите кнопку ниже, чтобы начать установку.";
                _pillConnVal.Text = "USB Boot OK";
                _pillConnBorder.BorderBrush = new SolidColorBrush(Color.FromArgb(90, 16, 185, 129));
            }
            else if (s.AdbConnected)
            {
                _statusDot.Fill = new SolidColorBrush(Color.FromRgb(56, 189, 248));
                _txtHeroStatus.Text = "Станция подключена по ADB (" + s.AdbSerial + ")";
                _txtHeroSub.Text = "Нажмите кнопку — установщик переведёт станцию в сервисный режим и обновит прошивку.";
                _pillConnVal.Text = "ADB: " + s.AdbSerial;
                _pillConnBorder.BorderBrush = new SolidColorBrush(Color.FromArgb(90, 56, 189, 248));
            }
            else
            {
                _statusDot.Fill = new SolidColorBrush(Color.FromRgb(245, 158, 11));
                _txtHeroStatus.Text = "Ожидание подключения станции";
                _txtHeroSub.Text = "Замкните 6-й контакт сервисного Type-C на GND, подключите USB к ПК и подайте питание 19В.";
                _pillConnVal.Text = "Не подключена";
                _pillConnBorder.BorderBrush = new SolidColorBrush(Color.FromArgb(40, 255, 255, 255));
            }

            _pillRomVal.Text = s.LocalRomVer;
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

        private void EnsureFlashToolsPresent()
        {
            string flashPy = Path.Combine(_toolsRoot, "service_flash", "flash_service.py");
            string updateExe = Path.Combine(_toolsRoot, "service_flash", "update.exe");
            string verFile = Path.Combine(_toolsRoot, "service_flash", "tools_version.txt");

            string appData = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "OpenStation"
            );
            bool isAppDataCache = string.Equals(
                Path.GetFullPath(_toolsRoot).TrimEnd('\\'),
                Path.GetFullPath(appData).TrimEnd('\\'),
                StringComparison.OrdinalIgnoreCase
            );

            bool versionMatches = false;
            if (File.Exists(verFile))
            {
                try
                {
                    versionMatches = string.Equals(File.ReadAllText(verFile).Trim(), FW_VERSION, StringComparison.OrdinalIgnoreCase);
                }
                catch { }
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
            SetProgress(2, "Проверка инструментов...");
            EnsureFlashToolsPresent();

            string py = FindPythonExe();
            if (string.IsNullOrEmpty(py))
                throw new Exception("На ПК не найден Python 3. Установите Python 3.10+ с python.org (Add to PATH) и повторите.");

            string pyPrefix = (py == "py") ? "-3 " : "";
            string scriptPath = Path.Combine(_toolsRoot, "service_flash", "flash_service.py");

            if (!snap.DriverOk)
            {
                SetProgress(5, "Установка драйвера WorldCup...");
                RunPythonStream(py, pyPrefix + "\"" + scriptPath + "\" --install-driver");
            }

            // Re-check connection after driver install
            snap = ProbeDeviceSnapshot();
            Dispatcher.Invoke(() => ApplySnapshotToUi(snap));

            if (!snap.UsbConnected && !snap.AdbConnected)
            {
                SetProgress(0, "");
                Dispatcher.Invoke(() =>
                {
                    _progressCard.Visibility = Visibility.Collapsed;
                    _statusDot.Fill = new SolidColorBrush(Color.FromRgb(245, 158, 11));
                    _txtHeroStatus.Text = "Станция всё ещё не обнаружена";
                    _txtHeroSub.Text = "Драйвер проверен. Убедитесь, что кабель вставлен в сервисный Type-C (6-й пин на GND) и подано питание 19В, затем нажмите кнопку снова.";
                });
                AppendLog("Проверка завершена: станция не обнаружена ни в USB Boot (1B8E:C003), ни по ADB.");
                return;
            }

            bool proceed = false;
            Dispatcher.Invoke(() =>
            {
                string modeLabel = snap.UsbConnected
                    ? "USB Boot (1B8E:C003)"
                    : "ADB (" + snap.AdbSerial + ") -> автоматический переход в USB Burn";
                var ans = MessageBox.Show(
                    "Станция готова к установке (" + modeLabel + ").\n\n" +
                    "Будет установлена актуальная прошивка OpenStation Max с разблокировкой загрузчика (silent=0, lock, avb2=0).\n\n" +
                    "НЕ отключайте питание и кабель во время процесса. Продолжить?",
                    "Подтверждение установки",
                    MessageBoxButton.YesNo,
                    MessageBoxImage.Question
                );
                proceed = (ans == MessageBoxResult.Yes);
            });

            if (!proceed)
            {
                AppendLog("Установка отменена пользователем после проверки подключения.");
                Dispatcher.Invoke(() => { _progressCard.Visibility = Visibility.Collapsed; });
                return;
            }

            AppendLog("Запуск полной установки через yasta_flasher движок...");
            int rc = RunPythonStream(py, pyPrefix + "\"" + scriptPath + "\" --flash");
            if (rc != 0)
                throw new Exception("Процесс прошивки завершился с ошибкой (код " + rc + "). Подробности в логе ниже.");

            SetProgress(100, "Прошивка успешно установлена!");
            Dispatcher.Invoke(() =>
            {
                _statusDot.Fill = new SolidColorBrush(Color.FromRgb(52, 211, 153));
                _txtHeroStatus.Text = "OpenStation Max успешно установлена!";
                _txtHeroSub.Text = "Отключите сервисный кабель и перемычку GND, затем перезапустите питание станции.";
            });
        }

        private int RunPythonStream(string exe, string args)
        {
            var psi = new ProcessStartInfo
            {
                FileName = exe,
                Arguments = args,
                WorkingDirectory = Path.Combine(_toolsRoot, "service_flash"),
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
            req.UserAgent = "OpenStationFlasher/1.5";
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
            req.UserAgent = "OpenStationFlasher/1.5";
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
