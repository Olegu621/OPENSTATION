using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Management;
using System.Net;
using System.Reflection;
using System.Security.Principal;
using System.Text;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using Path = System.IO.Path;
using System.IO.Compression;

namespace OpenStationFlasher
{
    public class App : Application
    {
        [STAThread]
        public static void Main(string[] args)
        {
            try
            {
                var app = new App();
                app.ShutdownMode = ShutdownMode.OnMainWindowClose;
                var window = new MainWindow();
                window.Show();
                app.Run(window);
            }
            catch (Exception ex)
            {
                try
                {
                    string logP = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "flasher_startup_error.log");
                    File.WriteAllText(logP, ex.ToString());
                    MessageBox.Show("Ошибка запуска OpenStation Flasher:\n\n" + ex.ToString(), "Ошибка", MessageBoxButton.OK, MessageBoxImage.Error);
                }
                catch { }
            }
        }
    }

    public class MainWindow : Window
    {
        private DispatcherTimer _pollTimer;
        private bool _isFlashing = false;
        private bool _isDeviceConnected = false;
        private bool _isAdbConnected = false;
        private bool _driverError = false;
        private string _driverErrDetail = "";
        private string _deviceName = "";
        private string _adbSerial = "";

        private string _baseDir;
        private string _serviceFlashDir;
        private string _romDir;

        // Current Wizard Step: 1, 2, 3, 4
        private int _currentStep = 1;

        // Selected Options
        private string _selectedRevision = "auto"; // "auto", "0008", "00053"
        private int _selectedMode = 0; // 0: Clean, 1: KeepData, 2: AfterUbt, 3: Adb

        // UI Panels for Wizard Steps
        private Grid _step1View;
        private Grid _step2View;
        private Grid _step3View;
        private Grid _step4View;

        // Stepper Visual Controls
        private Border[] _stepperCircles = new Border[4];
        private TextBlock[] _stepperNumbers = new TextBlock[4];
        private TextBlock[] _stepperLabels = new TextBlock[4];
        private Border[] _stepperLines = new Border[3];

        // Revision Selection Cards (Step 1)
        private Border _cardRevAuto;
        private Border _cardRev0008;
        private Border _cardRev0053;

        // Mode Selection Cards (Step 3)
        private Border _cardModeClean;
        private Border _cardModeKeep;
        private Border _cardModeAfterUbt;
        private Border _cardModeAdb;

        // Global Bottom Navigation Bar
        private Button _btnNavBack;
        private Button _btnNavNext;
        private Border _bottomDevicePill;
        private System.Windows.Shapes.Ellipse _bottomDeviceOrb;
        private TextBlock _bottomDeviceText;

        // Step 2 Live Status Indicators
        private Border _step2StatusBadge;
        private System.Windows.Shapes.Ellipse _step2StatusOrb;
        private TextBlock _step2StatusTitle;
        private TextBlock _step2StatusDesc;
        private Button _step2BtnInstallDriver;
        private Button _step2BtnAdbReboot;
        private Button _step2BtnUpdateEntry;

        // Step 3 Live Execution Controls
        private Button _btnStartFlashBig;
        private ProgressBar _progBar;
        private TextBlock _progText;
        private TextBlock _progSubtext;
        private Border _stageBadgesContainer;
        private TextBlock[] _stageLabels = new TextBlock[4];

        // Step 4 Completion Controls
        private Button _btnStep4WebUi;
        private Button _btnStep4TvScreen;
        private Button _btnStep4RestartWizard;
        private Button _btnStep4Exit;

        // Prerequisite indicators (Step 1)
        private TextBlock _valRom;
        private TextBlock _valUboot;
        private TextBlock _valDriver;
        private TextBlock _valPython;

        // Slide-over Modal / Card Containers
        private Border _modalOverlay;
        private Border _modalCard;
        private TextBlock _modalTitle;
        private TextBlock _modalBody;

        // Log Drawer
        private Border _logDrawer;
        private TextBox _logBox;
        private Button _btnToggleLog;
        private bool _isLogVisible = false;

        private string ResolveBaseDirectory()
        {
            string current = AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\');

            // 1. Direct check
            if (File.Exists(Path.Combine(current, "scripts", "flash_service_port.ps1")))
                return current;

            // 2. Subfolders
            string[] subfolders = new string[]
            {
                Path.Combine(current, "OPENSTATION-main"),
                Path.Combine(current, "OpenStationMax_Flasher_v1.3.5"),
                Path.Combine(current, "OpenStationMax_Flasher_v1.3.4"),
                Path.Combine(current, "OpenStationMax_Flasher_v1.3.3"),
                Path.Combine(current, "OpenStationMax_Flasher"),
                Path.Combine(current, "OPENSTATION")
            };
            foreach (var sub in subfolders)
            {
                if (File.Exists(Path.Combine(sub, "scripts", "flash_service_port.ps1")))
                    return sub;
            }

            // 3. Parent directory
            try
            {
                var parent = Directory.GetParent(current);
                if (parent != null && File.Exists(Path.Combine(parent.FullName, "scripts", "flash_service_port.ps1")))
                    return parent.FullName;
            }
            catch { }

            // 4. Fallback to known development workspace
            string devWorkspace = @"C:\Users\admin\.gemini\antigravity\scratch\OPENSTATION";
            if (File.Exists(Path.Combine(devWorkspace, "scripts", "flash_service_port.ps1")))
                return devWorkspace;

            // 5. Look for ZIP archive in current dir, Desktop or Downloads, and auto-extract!
            string[] zipCandidates = new string[]
            {
                Path.Combine(current, "OpenStationMax_Flasher_v1.3.5.zip"),
                Path.Combine(current, "OpenStationMax_Flasher_v1.3.4.zip"),
                Path.Combine(current, "OpenStationMax_Flasher_v1.3.3.zip"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "OpenStationMax_Flasher_v1.3.5.zip"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "OpenStationMax_Flasher_v1.3.4.zip"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "OPENSTATION-main.zip"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "OpenStationMax_Flasher_v1.3.4.zip")
            };

            foreach (var zip in zipCandidates)
            {
                if (File.Exists(zip))
                {
                    try
                    {
                        string targetDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "OpenStationMax");
                        if (!File.Exists(Path.Combine(targetDir, "scripts", "flash_service_port.ps1")))
                        {
                            System.IO.Compression.ZipFile.ExtractToDirectory(zip, targetDir);
                        }
                        if (File.Exists(Path.Combine(targetDir, "scripts", "flash_service_port.ps1")))
                            return targetDir;
                    }
                    catch { }
                }
            }

            return current;
        }

        private string GetScriptPath(string scriptName)
        {
            string ps1 = Path.Combine(_baseDir, "scripts", scriptName);
            if (File.Exists(ps1)) return ps1;

            string resolved = ResolveBaseDirectory();
            if (File.Exists(Path.Combine(resolved, "scripts", scriptName)))
            {
                _baseDir = resolved;
                _serviceFlashDir = Path.Combine(_baseDir, "service_flash");
                _romDir = Path.Combine(_baseDir, "rom");
                return Path.Combine(_baseDir, "scripts", scriptName);
            }

            throw new FileNotFoundException(
                "Файл скрипта не найден: scripts\\" + scriptName + "\n\n" +
                "Каталог поиска: " + _baseDir + "\n\n" +
                "ПРИЧИНА ОШИБКИ (-196608):\n" +
                "Программа запущена отдельно от папки с файлами прошивки.\n\n" +
                "КАК ИСПРАВИТЬ:\n" +
                "1. Распакуйте архив OpenStationMax_Flasher полностью в отдельную папку.\n" +
                "2. Убедитесь, что рядом с OpenStationFlasher.exe лежат папки 'scripts' и 'service_flash'.\n" +
                "3. Запустите OpenStationFlasher.exe внутри распакованной папки.");
        }

        public MainWindow()
        {
            _baseDir = ResolveBaseDirectory();
            _serviceFlashDir = Path.Combine(_baseDir, "service_flash");
            _romDir = Path.Combine(_baseDir, "rom");

            InitWindow();
            BuildUI();
            StartDevicePolling();

            Log("================================================================================");
            Log(" OpenStation Max Flasher v1.3.5 — Мастер пошаговой прошивки и разблокировки");
            Log(" Поддержка всех ревизий: YNDX-0008, YNDX-00052, YNDX-00053 (Zigbee 2023+)");
            Log(" Защита от бутлупа: автоматический обход AVB2, запись правильного misc и uboot-command");
            Log(" Каталог программы: " + _baseDir);
            Log("================================================================================");

            CheckPrerequisites();
            UpdateStepView();
        }

        private void InitWindow()
        {
            Title = "OpenStation Max — Мастер прошивки v1.3.5 [0008 / 00052 / 00053 Zigbee]";
            Width = 990;
            Height = 770;
            MinWidth = 880;
            MinHeight = 650;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            Background = new SolidColorBrush(Color.FromRgb(11, 14, 23)); // Deep Cosmic Glass
            Foreground = new SolidColorBrush(Color.FromRgb(240, 243, 248));
            FontFamily = new FontFamily("Segoe UI Variable, Segoe UI, sans-serif");

            KeyDown += (s, e) =>
            {
                if (e.Key == Key.Escape && _modalOverlay != null && _modalOverlay.Visibility == Visibility.Visible)
                {
                    _modalOverlay.Visibility = Visibility.Collapsed;
                }
            };
        }

        private static bool IsAdministrator()
        {
            try
            {
                var identity = WindowsIdentity.GetCurrent();
                var principal = new WindowsPrincipal(identity);
                return principal.IsInRole(WindowsBuiltInRole.Administrator);
            }
            catch { return false; }
        }

        private void RestartAsAdmin()
        {
            try
            {
                var exePath = Process.GetCurrentProcess().MainModule.FileName;
                var psi = new ProcessStartInfo
                {
                    FileName = exePath,
                    Verb = "runas",
                    UseShellExecute = true
                };
                Process.Start(psi);
                Application.Current.Shutdown();
            }
            catch (Exception ex)
            {
                MessageBox.Show("Не удалось повысить привилегии: " + ex.Message, "Ошибка UAC", MessageBoxButton.OK, MessageBoxImage.Warning);
            }
        }

        // ==========================================
        // UI CONSTRUCTION
        // ==========================================
        private void BuildUI()
        {
            var mainGrid = new Grid();
            mainGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // 0: Top Header
            mainGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // 1: Stepper Indicator Bar
            mainGrid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) }); // 2: Wizard Content (Steps 1..4)
            mainGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // 3: Log Drawer (Expandable)
            mainGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // 4: Bottom Navigation Bar

            // 0. TOP HEADER BAR
            mainGrid.Children.Add(BuildTopHeader());

            // 1. STEPPER INDICATOR BAR
            mainGrid.Children.Add(BuildStepperBar());

            // 2. WIZARD STEPS CONTAINER
            var contentContainer = new Grid { Margin = new Thickness(24, 8, 24, 8) };
            _step1View = BuildStep1View();
            _step2View = BuildStep2View();
            _step3View = BuildStep3View();
            _step4View = BuildStep4View();

            contentContainer.Children.Add(_step1View);
            contentContainer.Children.Add(_step2View);
            contentContainer.Children.Add(_step3View);
            contentContainer.Children.Add(_step4View);
            Grid.SetRow(contentContainer, 2);
            mainGrid.Children.Add(contentContainer);

            // 3. LOG DRAWER
            _logDrawer = BuildLogDrawer();
            Grid.SetRow(_logDrawer, 3);
            mainGrid.Children.Add(_logDrawer);

            // 4. BOTTOM NAVIGATION BAR
            mainGrid.Children.Add(BuildBottomNav());

            // MODAL OVERLAY (For Guide & Bootloop Details)
            _modalOverlay = BuildModalOverlay();
            mainGrid.Children.Add(_modalOverlay);

            Content = mainGrid;
        }

        // ==========================================
        // 0. TOP HEADER
        // ==========================================
        private FrameworkElement BuildTopHeader()
        {
            var header = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(160, 18, 24, 38)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(40, 255, 255, 255)),
                BorderThickness = new Thickness(0, 0, 0, 1),
                Padding = new Thickness(24, 12, 24, 12)
            };
            Grid.SetRow(header, 0);

            var grid = new Grid();
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            // Title Left
            var leftStack = new StackPanel { Orientation = Orientation.Horizontal };
            var logo = new TextBlock
            {
                Text = "⚡",
                FontSize = 24,
                Foreground = new SolidColorBrush(Color.FromRgb(0, 212, 255)),
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(0, 0, 10, 0)
            };
            leftStack.Children.Add(logo);

            var titleText = new TextBlock
            {
                Text = "OPENSTATION MAX",
                FontSize = 19,
                FontWeight = FontWeights.Bold,
                Foreground = new SolidColorBrush(Color.FromRgb(255, 255, 255)),
                VerticalAlignment = VerticalAlignment.Center
            };
            leftStack.Children.Add(titleText);

            var verBadge = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(40, 0, 212, 255)),
                BorderBrush = new SolidColorBrush(Color.FromRgb(0, 212, 255)),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(12),
                Margin = new Thickness(12, 0, 0, 0),
                Padding = new Thickness(9, 2, 9, 2),
                VerticalAlignment = VerticalAlignment.Center
            };
            verBadge.Child = new TextBlock
            {
                Text = "v1.3.5 Мастер Установки",
                FontSize = 11,
                FontWeight = FontWeights.SemiBold,
                Foreground = new SolidColorBrush(Color.FromRgb(0, 212, 255))
            };
            leftStack.Children.Add(verBadge);

            if (IsAdministrator())
            {
                var adminBadge = new Border
                {
                    Background = new SolidColorBrush(Color.FromArgb(35, 46, 160, 67)),
                    BorderBrush = new SolidColorBrush(Color.FromRgb(46, 160, 67)),
                    BorderThickness = new Thickness(1),
                    CornerRadius = new CornerRadius(12),
                    Margin = new Thickness(8, 0, 0, 0),
                    Padding = new Thickness(8, 2, 8, 2),
                    VerticalAlignment = VerticalAlignment.Center
                };
                adminBadge.Child = new TextBlock
                {
                    Text = "🛡 Admin",
                    FontSize = 10.5,
                    FontWeight = FontWeights.SemiBold,
                    Foreground = new SolidColorBrush(Color.FromRgb(63, 185, 80))
                };
                leftStack.Children.Add(adminBadge);
            }
            else
            {
                var btnAdmin = CreateSmallButton("🛡 Запустить как Admin", Color.FromRgb(60, 40, 15), Color.FromRgb(255, 210, 100));
                btnAdmin.Margin = new Thickness(8, 0, 0, 0);
                btnAdmin.Click += (s, e) => RestartAsAdmin();
                leftStack.Children.Add(btnAdmin);
            }

            Grid.SetColumn(leftStack, 0);
            grid.Children.Add(leftStack);

            // Header Right Buttons
            var rightStack = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };

            var btnGuideInfo = CreateSmallButton("📖 Руководство", Color.FromRgb(36, 22, 54), Color.FromRgb(216, 180, 254));
            btnGuideInfo.Margin = new Thickness(0, 0, 8, 0);
            btnGuideInfo.Click += (s, e) => ShowModal("📖 Руководство пользователя OpenStation Max", GetGuideHelpText());
            rightStack.Children.Add(btnGuideInfo);

            var btnBootloopInfo = CreateSmallButton("🛡 Защита от бутлупа", Color.FromRgb(16, 42, 32), Color.FromRgb(74, 222, 128));
            btnBootloopInfo.Margin = new Thickness(0, 0, 8, 0);
            btnBootloopInfo.Click += (s, e) => ShowModal("🛡 Как решена проблема бутлупа в v1.3.5", GetBootloopHelpText());
            rightStack.Children.Add(btnBootloopInfo);

            var btnWeb = CreateSmallButton("🌐 Веб-пульт", Color.FromRgb(18, 30, 48), Color.FromRgb(88, 166, 255));
            btnWeb.Click += (s, e) => OpenWebUi();
            rightStack.Children.Add(btnWeb);

            Grid.SetColumn(rightStack, 1);
            grid.Children.Add(rightStack);

            header.Child = grid;
            return header;
        }

        // ==========================================
        // 1. STEPPER INDICATOR BAR
        // ==========================================
        private FrameworkElement BuildStepperBar()
        {
            var bar = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(100, 14, 18, 28)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(30, 255, 255, 255)),
                BorderThickness = new Thickness(0, 0, 0, 1),
                Padding = new Thickness(32, 12, 32, 12)
            };
            Grid.SetRow(bar, 1);

            var grid = new Grid();
            for (int i = 0; i < 7; i++)
            {
                if (i % 2 == 0) grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); // Step item
                else grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) }); // Line connector
            }

            string[] stepTitles = new string[]
            {
                "1. Ревизия Станции",
                "2. Подключение и Драйвер",
                "3. Прошивка и Процесс",
                "4. Завершение и Старт"
            };

            for (int i = 0; i < 4; i++)
            {
                int stepNum = i + 1;
                var stepPanel = new StackPanel
                {
                    Orientation = Orientation.Horizontal,
                    Cursor = Cursors.Hand,
                    Background = Brushes.Transparent,
                    VerticalAlignment = VerticalAlignment.Center
                };

                int targetStep = stepNum;
                stepPanel.MouseDown += (s, e) =>
                {
                    if (!_isFlashing) GoToStep(targetStep);
                };

                var circle = new Border
                {
                    Width = 28,
                    Height = 28,
                    CornerRadius = new CornerRadius(14),
                    Background = new SolidColorBrush(Color.FromRgb(30, 36, 48)),
                    BorderBrush = new SolidColorBrush(Color.FromRgb(60, 70, 90)),
                    BorderThickness = new Thickness(1.5),
                    Margin = new Thickness(0, 0, 8, 0)
                };

                var numText = new TextBlock
                {
                    Text = stepNum.ToString(),
                    FontSize = 12,
                    FontWeight = FontWeights.Bold,
                    Foreground = new SolidColorBrush(Color.FromRgb(180, 190, 205)),
                    HorizontalAlignment = HorizontalAlignment.Center,
                    VerticalAlignment = VerticalAlignment.Center
                };
                circle.Child = numText;
                _stepperCircles[i] = circle;
                _stepperNumbers[i] = numText;
                stepPanel.Children.Add(circle);

                var label = new TextBlock
                {
                    Text = stepTitles[i],
                    FontSize = 12.5,
                    FontWeight = FontWeights.SemiBold,
                    Foreground = new SolidColorBrush(Color.FromRgb(140, 150, 165)),
                    VerticalAlignment = VerticalAlignment.Center
                };
                _stepperLabels[i] = label;
                stepPanel.Children.Add(label);

                Grid.SetColumn(stepPanel, i * 2);
                grid.Children.Add(stepPanel);

                // Add Connector line
                if (i < 3)
                {
                    var line = new Border
                    {
                        Height = 2,
                        Background = new SolidColorBrush(Color.FromArgb(40, 255, 255, 255)),
                        Margin = new Thickness(12, 0, 12, 0),
                        VerticalAlignment = VerticalAlignment.Center
                    };
                    _stepperLines[i] = line;
                    Grid.SetColumn(line, i * 2 + 1);
                    grid.Children.Add(line);
                }
            }

            bar.Child = grid;
            return bar;
        }

        private void UpdateStepperUI()
        {
            for (int i = 0; i < 4; i++)
            {
                int stepNum = i + 1;
                if (stepNum < _currentStep)
                {
                    // Completed step
                    _stepperCircles[i].Background = new SolidColorBrush(Color.FromArgb(60, 16, 185, 129));
                    _stepperCircles[i].BorderBrush = new SolidColorBrush(Color.FromRgb(16, 185, 129));
                    _stepperNumbers[i].Text = "✓";
                    _stepperNumbers[i].Foreground = new SolidColorBrush(Color.FromRgb(52, 211, 153));
                    _stepperLabels[i].Foreground = new SolidColorBrush(Color.FromRgb(200, 210, 225));
                }
                else if (stepNum == _currentStep)
                {
                    // Current active step
                    _stepperCircles[i].Background = new SolidColorBrush(Color.FromArgb(70, 0, 212, 255));
                    _stepperCircles[i].BorderBrush = new SolidColorBrush(Color.FromRgb(0, 212, 255));
                    _stepperNumbers[i].Text = stepNum.ToString();
                    _stepperNumbers[i].Foreground = new SolidColorBrush(Color.FromRgb(255, 255, 255));
                    _stepperLabels[i].Foreground = new SolidColorBrush(Color.FromRgb(0, 212, 255));
                }
                else
                {
                    // Future step
                    _stepperCircles[i].Background = new SolidColorBrush(Color.FromRgb(20, 26, 36));
                    _stepperCircles[i].BorderBrush = new SolidColorBrush(Color.FromArgb(45, 255, 255, 255));
                    _stepperNumbers[i].Text = stepNum.ToString();
                    _stepperNumbers[i].Foreground = new SolidColorBrush(Color.FromRgb(120, 130, 145));
                    _stepperLabels[i].Foreground = new SolidColorBrush(Color.FromRgb(120, 130, 145));
                }

                if (i < 3)
                {
                    if (stepNum < _currentStep)
                    {
                        _stepperLines[i].Background = new SolidColorBrush(Color.FromRgb(16, 185, 129));
                    }
                    else
                    {
                        _stepperLines[i].Background = new SolidColorBrush(Color.FromArgb(40, 255, 255, 255));
                    }
                }
            }
        }

        // ==========================================
        // 2. STEP 1 VIEW: REVISION SELECTION
        // ==========================================
        private Grid BuildStep1View()
        {
            var grid = new Grid();
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // Title & intro
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // 3 Revision Cards
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // Bottom Info / Sticker Guide
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // Prerequisites Bar (Compact)
            grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) }); // Spacer

            // Title
            var titleBox = new StackPanel { Margin = new Thickness(0, 8, 0, 16) };
            var h1 = new TextBlock
            {
                Text = "Шаг 1 из 4: Выберите ревизию вашей Яндекс Станции Макс",
                FontSize = 18,
                FontWeight = FontWeights.Bold,
                Foreground = new SolidColorBrush(Color.FromRgb(255, 255, 255))
            };
            titleBox.Children.Add(h1);

            var p1 = new TextBlock
            {
                Text = "Прошивальщик автоматически применит подходящий загрузчик U-Boot и конфигурацию ядра для исключения бутлупа.",
                FontSize = 12.5,
                Foreground = new SolidColorBrush(Color.FromRgb(148, 163, 184)),
                Margin = new Thickness(0, 4, 0, 0)
            };
            titleBox.Children.Add(p1);
            Grid.SetRow(titleBox, 0);
            grid.Children.Add(titleBox);

            // 3 Revision Cards (Horizontal Layout)
            var cardsGrid = new Grid { Margin = new Thickness(0, 0, 0, 16) };
            cardsGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            cardsGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            cardsGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            _cardRevAuto = CreateSelectableCard(
                "✨ Автоопределение",
                "Рекомендуется для всех",
                "Программа сама определит тип чипа Amlogic (classic vs rev375f) и подставит правильный загрузчик и параметры загрузки.",
                "Безопасно",
                Color.FromRgb(0, 212, 255),
                true);
            _cardRevAuto.MouseDown += (s, e) => SelectRevision("auto");
            Grid.SetColumn(_cardRevAuto, 0);
            cardsGrid.Children.Add(_cardRevAuto);

            _cardRev0008 = CreateSelectableCard(
                "📻 YNDX-0008 / 00052",
                "Классическая Станция Макс",
                "Первая ревизия (выпуск 2020–2022 гг.) без встроенного Zigbee-хаба. Использует базовый загрузчик aml.img.",
                "Классика",
                Color.FromRgb(168, 85, 247),
                false);
            _cardRev0008.MouseDown += (s, e) => SelectRevision("0008");
            Grid.SetColumn(_cardRev0008, 1);
            cardsGrid.Children.Add(_cardRev0008);

            _cardRev0053 = CreateSelectableCard(
                "📶 YNDX-00053 Zigbee",
                "Станция Макс с Zigbee",
                "Новая ревизия (2023+ г.) со встроенным модулем Zigbee. Требует загрузчик aml_00053.img и дерево устройств rev375f.",
                "Zigbee 2023+",
                Color.FromRgb(16, 185, 129),
                false);
            _cardRev0053.MouseDown += (s, e) => SelectRevision("00053");
            Grid.SetColumn(_cardRev0053, 2);
            cardsGrid.Children.Add(_cardRev0053);

            Grid.SetRow(cardsGrid, 1);
            grid.Children.Add(cardsGrid);

            // Bottom Sticker Guide Card
            var stickerCard = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(120, 18, 24, 38)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(40, 255, 255, 255)),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(16),
                Padding = new Thickness(16, 12, 16, 12),
                Margin = new Thickness(0, 0, 0, 16)
            };
            var stGrid = new Grid();
            stGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            stGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            var stIcon = new TextBlock
            {
                Text = "💡",
                FontSize = 24,
                Margin = new Thickness(0, 0, 12, 0),
                VerticalAlignment = VerticalAlignment.Center
            };
            Grid.SetColumn(stIcon, 0);
            stGrid.Children.Add(stIcon);

            var stTextStack = new StackPanel();
            var stTitle = new TextBlock
            {
                Text = "Где посмотреть ревизию вашей Станции?",
                FontSize = 13,
                FontWeight = FontWeights.Bold,
                Foreground = new SolidColorBrush(Color.FromRgb(240, 243, 248))
            };
            stTextStack.Children.Add(stTitle);

            var stDesc = new TextBlock
            {
                Text = "Переверните колонку: на круглой наклейке снизу корпуса (под резиновой ножкой) указана точная модель — YNDX-0008, YNDX-00052 или YNDX-00053. Если сомневаетесь, оставьте «Автоопределение».",
                FontSize = 12,
                Foreground = new SolidColorBrush(Color.FromRgb(148, 163, 184)),
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 3, 0, 0)
            };
            stTextStack.Children.Add(stDesc);
            Grid.SetColumn(stTextStack, 1);
            stGrid.Children.Add(stTextStack);

            stickerCard.Child = stGrid;
            Grid.SetRow(stickerCard, 2);
            grid.Children.Add(stickerCard);

            // Prerequisites Bar
            var prereqBar = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(80, 15, 20, 32)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(30, 255, 255, 255)),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(14),
                Padding = new Thickness(16, 10, 16, 10)
            };
            var pGrid = new Grid();
            pGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            pGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            pGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            pGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            var p1Badge = CreatePrereqItem("📦 Образы ROM + misc", out _valRom);
            Grid.SetColumn(p1Badge, 0);
            pGrid.Children.Add(p1Badge);

            var p2Badge = CreatePrereqItem("💾 Загрузчик U-Boot", out _valUboot);
            Grid.SetColumn(p2Badge, 1);
            pGrid.Children.Add(p2Badge);

            var p3Badge = CreatePrereqItem("🔧 Драйвер WorldCup", out _valDriver);
            Grid.SetColumn(p3Badge, 2);
            pGrid.Children.Add(p3Badge);

            var p4Badge = CreatePrereqItem("🐍 Движок BootROM", out _valPython);
            Grid.SetColumn(p4Badge, 3);
            pGrid.Children.Add(p4Badge);

            prereqBar.Child = pGrid;
            Grid.SetRow(prereqBar, 3);
            grid.Children.Add(prereqBar);

            return grid;
        }

        private void SelectRevision(string rev)
        {
            _selectedRevision = rev;
            SetCardSelected(_cardRevAuto, rev == "auto", Color.FromRgb(0, 212, 255));
            SetCardSelected(_cardRev0008, rev == "0008", Color.FromRgb(168, 85, 247));
            SetCardSelected(_cardRev0053, rev == "00053", Color.FromRgb(16, 185, 129));
            Log("[*] Выбрана ревизия Станции: " + rev);
        }

        // ==========================================
        // 3. STEP 2 VIEW: CONNECTION & DRIVER
        // ==========================================
        private Grid BuildStep2View()
        {
            var grid = new Grid();
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // Title & intro
            grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) }); // 2 Columns: Left Pinout / Right Live Status

            // Title
            var titleBox = new StackPanel { Margin = new Thickness(0, 8, 0, 14) };
            var h1 = new TextBlock
            {
                Text = "Шаг 2 из 4: Подключение кабеля и проверка драйвера",
                FontSize = 18,
                FontWeight = FontWeights.Bold,
                Foreground = new SolidColorBrush(Color.FromRgb(255, 255, 255))
            };
            titleBox.Children.Add(h1);

            var p1 = new TextBlock
            {
                Text = "Для прошивки Станция Макс переводится в сервисный режим Amlogic BootROM (WorldCup Device).",
                FontSize = 12.5,
                Foreground = new SolidColorBrush(Color.FromRgb(148, 163, 184)),
                Margin = new Thickness(0, 4, 0, 0)
            };
            titleBox.Children.Add(p1);
            Grid.SetRow(titleBox, 0);
            grid.Children.Add(titleBox);

            // 2 Columns
            var colsGrid = new Grid();
            colsGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.15, GridUnitType.Star) }); // Left: Wiring Diagram
            colsGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.85, GridUnitType.Star) }); // Right: Live Status & Driver

            // LEFT: Wiring & Pinout Guide
            var wiringCard = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(160, 16, 22, 34)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(45, 255, 255, 255)),
                BorderThickness = new Thickness(1.2),
                CornerRadius = new CornerRadius(18),
                Padding = new Thickness(18, 14, 18, 14),
                Margin = new Thickness(0, 0, 10, 0)
            };
            var wiringStack = new StackPanel();

            var wTitle = new TextBlock
            {
                Text = "📐 Схема 7-контактного сервисного разъёма",
                FontSize = 13.5,
                FontWeight = FontWeights.Bold,
                Foreground = new SolidColorBrush(Color.FromRgb(0, 212, 255)),
                Margin = new Thickness(0, 0, 0, 8)
            };
            wiringStack.Children.Add(wTitle);

            string pinoutArt =
                "Нумерация контактов [1]..[7] считается от разъёма AUX 3.5mm:\n\n" +
                "  [AUX]   (1)       (2)       (3)       (4)       (5)       (6)       (7)\n" +
                "           │         │         │                             │\n" +
                "         DATA+     DATA-      GND ───────────────────────── BOOT\n" +
                "        (Зеленый) (Белый)   (Черный)   [Замкните пины 6 и 3 скрепкой!]\n";

            var pinoutText = new TextBlock
            {
                Text = pinoutArt,
                FontSize = 11.5,
                LineHeight = 17,
                Foreground = new SolidColorBrush(Color.FromRgb(226, 232, 240)),
                FontFamily = new FontFamily("Consolas, Courier New, monospace"),
                Background = new SolidColorBrush(Color.FromRgb(10, 13, 19)),
                Padding = new Thickness(10, 8, 10, 8)
            };
            wiringStack.Children.Add(pinoutText);

            // Warning Box: Red +5V Danger
            var warnBox = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(70, 239, 68, 68)),
                BorderBrush = new SolidColorBrush(Color.FromRgb(239, 68, 68)),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(10),
                Padding = new Thickness(12, 8, 12, 8),
                Margin = new Thickness(0, 10, 0, 0)
            };
            var warnText = new TextBlock
            {
                Text = "⛔ КРАСНЫЙ ПРОВОД (+5V USB) НЕ ПОДКЛЮЧАТЬ!\nПитание подаётся СТРОГО через штатный 19V адаптер в круглый разъём колонки.",
                FontSize = 11.5,
                FontWeight = FontWeights.Bold,
                Foreground = new SolidColorBrush(Color.FromRgb(254, 202, 202)),
                TextWrapping = TextWrapping.Wrap
            };
            warnBox.Child = warnText;
            wiringStack.Children.Add(warnBox);

            // 3-step checklist
            var checkStack = new StackPanel { Margin = new Thickness(0, 10, 0, 0) };
            checkStack.Children.Add(CreateCheckItem("1. Замкните пины 6 (BOOT) и 3 (GND) скрепкой или пинцетом"));
            checkStack.Children.Add(CreateCheckItem("2. Подключите USB-кабель (3 провода) к компьютеру"));
            checkStack.Children.Add(CreateCheckItem("3. Вставьте штатный штекер питания 19V в розетку и колонку"));
            wiringStack.Children.Add(checkStack);

            wiringCard.Child = wiringStack;
            Grid.SetColumn(wiringCard, 0);
            colsGrid.Children.Add(wiringCard);

            // RIGHT: Live Detection & Driver Actions
            var statusCol = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(160, 16, 22, 34)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(45, 255, 255, 255)),
                BorderThickness = new Thickness(1.2),
                CornerRadius = new CornerRadius(18),
                Padding = new Thickness(18, 14, 18, 14),
                Margin = new Thickness(10, 0, 0, 0)
            };
            var rightStack = new StackPanel();

            var rTitle = new TextBlock
            {
                Text = "📡 Статус подключения устройства",
                FontSize = 13.5,
                FontWeight = FontWeights.Bold,
                Foreground = new SolidColorBrush(Color.FromRgb(240, 243, 248)),
                Margin = new Thickness(0, 0, 0, 10)
            };
            rightStack.Children.Add(rTitle);

            // Step 2 Live Status Card
            _step2StatusBadge = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(140, 22, 28, 42)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(50, 255, 255, 255)),
                BorderThickness = new Thickness(1.2),
                CornerRadius = new CornerRadius(14),
                Padding = new Thickness(14, 12, 14, 12),
                Margin = new Thickness(0, 0, 0, 12)
            };
            var badgeGrid = new Grid();
            badgeGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            badgeGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            _step2StatusOrb = new System.Windows.Shapes.Ellipse
            {
                Width = 14,
                Height = 14,
                Fill = new SolidColorBrush(Color.FromRgb(245, 158, 11)),
                Margin = new Thickness(0, 3, 10, 0),
                VerticalAlignment = VerticalAlignment.Top
            };
            Grid.SetColumn(_step2StatusOrb, 0);
            badgeGrid.Children.Add(_step2StatusOrb);

            var sTextStack = new StackPanel();
            _step2StatusTitle = new TextBlock
            {
                Text = "Ожидание подключения...",
                FontSize = 13,
                FontWeight = FontWeights.Bold,
                Foreground = new SolidColorBrush(Color.FromRgb(245, 158, 11))
            };
            sTextStack.Children.Add(_step2StatusTitle);

            _step2StatusDesc = new TextBlock
            {
                Text = "Замкните пины 6 и 3, воткните USB в ПК и подключите 19V питание.",
                FontSize = 11.5,
                Foreground = new SolidColorBrush(Color.FromRgb(148, 163, 184)),
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 3, 0, 0)
            };
            sTextStack.Children.Add(_step2StatusDesc);
            Grid.SetColumn(sTextStack, 1);
            badgeGrid.Children.Add(sTextStack);

            _step2StatusBadge.Child = badgeGrid;
            rightStack.Children.Add(_step2StatusBadge);

            // Driver Action Button
            _step2BtnInstallDriver = CreateActionButton("🔧 Установить драйвер WorldCup", Color.FromRgb(16, 185, 129), Color.FromRgb(16, 48, 38));
            _step2BtnInstallDriver.Margin = new Thickness(0, 0, 0, 8);
            _step2BtnInstallDriver.Click += (s, e) => RunInstallDriver();
            rightStack.Children.Add(_step2BtnInstallDriver);

            // ADB Reboot Action Button
            _step2BtnAdbReboot = CreateActionButton("🔄 В режим прошивки (adb reboot update)", Color.FromRgb(56, 189, 248), Color.FromRgb(14, 38, 60));
            _step2BtnAdbReboot.Margin = new Thickness(0, 0, 0, 8);
            _step2BtnAdbReboot.Visibility = Visibility.Collapsed;
            _step2BtnAdbReboot.Click += (s, e) => RebootToBootloader();
            rightStack.Children.Add(_step2BtnAdbReboot);

            // Manual Boot-G12 Entry Action
            _step2BtnUpdateEntry = CreateActionButton("🚀 Войти в режим Update (boot-g12)", Color.FromRgb(168, 85, 247), Color.FromRgb(36, 20, 52));
            _step2BtnUpdateEntry.Margin = new Thickness(0, 0, 0, 8);
            _step2BtnUpdateEntry.Click += (s, e) => RunUpdateEntry();
            rightStack.Children.Add(_step2BtnUpdateEntry);

            var hintText = new TextBlock
            {
                Text = "ℹ Когда устройство подключится, индикатор станет зелёным и кнопка «Далее ▶» станет активной.",
                FontSize = 11,
                Foreground = new SolidColorBrush(Color.FromRgb(120, 130, 145)),
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 6, 0, 0)
            };
            rightStack.Children.Add(hintText);

            statusCol.Child = rightStack;
            Grid.SetColumn(statusCol, 1);
            colsGrid.Children.Add(statusCol);

            Grid.SetRow(colsGrid, 1);
            grid.Children.Add(colsGrid);

            return grid;
        }

        private TextBlock CreateCheckItem(string text)
        {
            return new TextBlock
            {
                Text = text,
                FontSize = 12,
                Foreground = new SolidColorBrush(Color.FromRgb(203, 213, 225)),
                Margin = new Thickness(0, 3, 0, 3)
            };
        }

        // ==========================================
        // 4. STEP 3 VIEW: FLASH EXECUTION & PROGRESS
        // ==========================================
        private Grid BuildStep3View()
        {
            var grid = new Grid();
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // Title
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // 4 Mode Cards (2x2 Grid)
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // Big Flash Action Button
            grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) }); // Progress Bar & Stages

            // Title
            var titleBox = new StackPanel { Margin = new Thickness(0, 8, 0, 12) };
            var h1 = new TextBlock
            {
                Text = "Шаг 3 из 4: Выбор типа прошивки и запуск",
                FontSize = 18,
                FontWeight = FontWeights.Bold,
                Foreground = new SolidColorBrush(Color.FromRgb(255, 255, 255))
            };
            titleBox.Children.Add(h1);

            var p1 = new TextBlock
            {
                Text = "Выберите режим установки и нажмите кнопку старта. Все файлы и разделы подготовлены автоматически.",
                FontSize = 12.5,
                Foreground = new SolidColorBrush(Color.FromRgb(148, 163, 184)),
                Margin = new Thickness(0, 4, 0, 0)
            };
            titleBox.Children.Add(p1);
            Grid.SetRow(titleBox, 0);
            grid.Children.Add(titleBox);

            // 4 Mode Cards (2x2)
            var modesGrid = new Grid { Margin = new Thickness(0, 0, 0, 12) };
            modesGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            modesGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            modesGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            modesGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

            _cardModeClean = CreateSelectableCard(
                "⚡ Чистая сервисная прошивка",
                "Полный Unbrick & Сброс",
                "Полная запись vbmeta, vendor, system, misc и форматирование /data. Устраняет любые сбои и бутлуп.",
                "Рекомендуется",
                Color.FromRgb(0, 212, 255),
                true);
            _cardModeClean.MouseDown += (s, e) => SelectFlashMode(0);
            Grid.SetRow(_cardModeClean, 0);
            Grid.SetColumn(_cardModeClean, 0);
            modesGrid.Children.Add(_cardModeClean);

            _cardModeKeep = CreateSelectableCard(
                "🔄 Обновление системы",
                "Сохранить данные /data",
                "Прошивка системных разделов с сохранением настроек, токенов и файлов на разделе /data.",
                "Без сброса",
                Color.FromRgb(168, 85, 247),
                false);
            _cardModeKeep.MouseDown += (s, e) => SelectFlashMode(1);
            Grid.SetRow(_cardModeKeep, 0);
            Grid.SetColumn(_cardModeKeep, 1);
            modesGrid.Children.Add(_cardModeKeep);

            _cardModeAfterUbt = CreateSelectableCard(
                "🛠 Снятие бутлупа (After UBT)",
                "Мгновенный фикс логотипа Яндекса",
                "Исправляет зависание после USB Burning Tool (~15 сек): запись правильного misc и снятие AVB2.",
                "Быстрый фикс",
                Color.FromRgb(245, 158, 11),
                false);
            _cardModeAfterUbt.MouseDown += (s, e) => SelectFlashMode(2);
            Grid.SetRow(_cardModeAfterUbt, 1);
            Grid.SetColumn(_cardModeAfterUbt, 0);
            modesGrid.Children.Add(_cardModeAfterUbt);

            _cardModeAdb = CreateSelectableCard(
                "📲 Быстрая установка по ADB",
                "Для работающих станций",
                "Установка лаунчера OpenStation, веб-пульта и демона прямо по сети или USB без разборки.",
                "Без разборки",
                Color.FromRgb(56, 189, 248),
                false);
            _cardModeAdb.MouseDown += (s, e) => SelectFlashMode(3);
            Grid.SetRow(_cardModeAdb, 1);
            Grid.SetColumn(_cardModeAdb, 1);
            modesGrid.Children.Add(_cardModeAdb);

            Grid.SetRow(modesGrid, 1);
            grid.Children.Add(modesGrid);

            // Big Start Button
            _btnStartFlashBig = new Button
            {
                Content = "⚡ ЗАПУСТИТЬ ПРОШИВКУ СТАНЦИИ МАКС",
                Height = 48,
                FontSize = 14.5,
                FontWeight = FontWeights.Bold,
                Cursor = Cursors.Hand,
                Margin = new Thickness(0, 0, 0, 12),
                Style = CreateAppleGlassButtonStyle(Color.FromRgb(0, 212, 255), Color.FromRgb(10, 20, 30), 24, true)
            };
            _btnStartFlashBig.Click += (s, e) => TriggerFlashExecution();
            Grid.SetRow(_btnStartFlashBig, 2);
            grid.Children.Add(_btnStartFlashBig);

            // Progress Section
            var progContainer = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(140, 16, 22, 34)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(40, 255, 255, 255)),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(16),
                Padding = new Thickness(16, 12, 16, 12)
            };
            var pStack = new StackPanel();

            var pHeader = new Grid();
            pHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            pHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            _progText = new TextBlock
            {
                Text = "Готов к запуску. Нажмите кнопку выше для старта.",
                FontSize = 13,
                FontWeight = FontWeights.SemiBold,
                Foreground = new SolidColorBrush(Color.FromRgb(240, 243, 248))
            };
            Grid.SetColumn(_progText, 0);
            pHeader.Children.Add(_progText);

            _progSubtext = new TextBlock
            {
                Text = "0%",
                FontSize = 13,
                FontWeight = FontWeights.Bold,
                Foreground = new SolidColorBrush(Color.FromRgb(0, 212, 255))
            };
            Grid.SetColumn(_progSubtext, 1);
            pHeader.Children.Add(_progSubtext);
            pStack.Children.Add(pHeader);

            _progBar = new ProgressBar
            {
                Height = 8,
                Minimum = 0,
                Maximum = 100,
                Value = 0,
                Foreground = new SolidColorBrush(Color.FromRgb(0, 212, 255)),
                Background = new SolidColorBrush(Color.FromRgb(30, 36, 48)),
                BorderThickness = new Thickness(0),
                Margin = new Thickness(0, 8, 0, 10)
            };
            pStack.Children.Add(_progBar);

            // Stage Checkpoints
            _stageBadgesContainer = new Border();
            var stagesGrid = new Grid();
            stagesGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            stagesGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            stagesGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            stagesGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            string[] stageNames = new string[] { "1. Проверка окружения", "2. Загрузка U-Boot", "3. Запись разделов ROM", "4. Финализация U-Boot" };
            for (int i = 0; i < 4; i++)
            {
                var lbl = new TextBlock
                {
                    Text = "○ " + stageNames[i],
                    FontSize = 11,
                    Foreground = new SolidColorBrush(Color.FromRgb(120, 130, 145)),
                    HorizontalAlignment = HorizontalAlignment.Center
                };
                _stageLabels[i] = lbl;
                Grid.SetColumn(lbl, i);
                stagesGrid.Children.Add(lbl);
            }
            _stageBadgesContainer.Child = stagesGrid;
            pStack.Children.Add(_stageBadgesContainer);

            progContainer.Child = pStack;
            Grid.SetRow(progContainer, 3);
            grid.Children.Add(progContainer);

            return grid;
        }

        private void SelectFlashMode(int mode)
        {
            _selectedMode = mode;
            SetCardSelected(_cardModeClean, mode == 0, Color.FromRgb(0, 212, 255));
            SetCardSelected(_cardModeKeep, mode == 1, Color.FromRgb(168, 85, 247));
            SetCardSelected(_cardModeAfterUbt, mode == 2, Color.FromRgb(245, 158, 11));
            SetCardSelected(_cardModeAdb, mode == 3, Color.FromRgb(56, 189, 248));

            string[] btnTitles = new string[]
            {
                "⚡ ЗАПУСТИТЬ ЧИСТУЮ ПРОШИВКУ (WIPE + UNBRICK)",
                "🔄 ЗАПУСТИТЬ ОБНОВЛЕНИЕ (СОХРАНИТЬ /DATA)",
                "🛠 ЗАПУСТИТЬ СНЯТИЕ БУТЛУПА (AFTER UBT)",
                "📲 ЗАПУСТИТЬ УСТАНОВКУ ПО ADB"
            };
            Color[] colors = new Color[]
            {
                Color.FromRgb(0, 212, 255),
                Color.FromRgb(168, 85, 247),
                Color.FromRgb(245, 158, 11),
                Color.FromRgb(56, 189, 248)
            };

            _btnStartFlashBig.Content = btnTitles[mode];
            _btnStartFlashBig.Style = CreateAppleGlassButtonStyle(colors[mode], Color.FromRgb(10, 20, 30), 24, true);
        }

        private void TriggerFlashExecution()
        {
            if (_selectedMode == 0) StartFlash(true);
            else if (_selectedMode == 1) StartFlash(false);
            else if (_selectedMode == 2) RunAfterUbt();
            else if (_selectedMode == 3) RunAdbFlash();
        }

        private void UpdateStageCheckpoint(int activeStage)
        {
            Dispatcher.Invoke(() =>
            {
                string[] stageNames = new string[] { "Проверка окружения", "Загрузка U-Boot", "Запись разделов ROM", "Финализация U-Boot" };
                for (int i = 0; i < 4; i++)
                {
                    if (i < activeStage)
                    {
                        _stageLabels[i].Text = "✓ " + stageNames[i];
                        _stageLabels[i].Foreground = new SolidColorBrush(Color.FromRgb(16, 185, 129));
                    }
                    else if (i == activeStage)
                    {
                        _stageLabels[i].Text = "● " + stageNames[i];
                        _stageLabels[i].Foreground = new SolidColorBrush(Color.FromRgb(0, 212, 255));
                    }
                    else
                    {
                        _stageLabels[i].Text = "○ " + stageNames[i];
                        _stageLabels[i].Foreground = new SolidColorBrush(Color.FromRgb(120, 130, 145));
                    }
                }
            });
        }

        // ==========================================
        // 5. STEP 4 VIEW: SUCCESS & FIRST START
        // ==========================================
        private Grid BuildStep4View()
        {
            var grid = new Grid();
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // Header Card
            grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) }); // Instructions Box
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // Action Buttons

            // Header Card
            var headerCard = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(160, 16, 48, 32)),
                BorderBrush = new SolidColorBrush(Color.FromRgb(16, 185, 129)),
                BorderThickness = new Thickness(1.5),
                CornerRadius = new CornerRadius(18),
                Padding = new Thickness(20, 16, 20, 16),
                Margin = new Thickness(0, 8, 0, 14)
            };
            var hStack = new StackPanel { Orientation = Orientation.Horizontal };
            var hIcon = new TextBlock
            {
                Text = "🎉",
                FontSize = 36,
                Margin = new Thickness(0, 0, 16, 0),
                VerticalAlignment = VerticalAlignment.Center
            };
            hStack.Children.Add(hIcon);

            var hTextStack = new StackPanel();
            var hTitle = new TextBlock
            {
                Text = "Поздравляем! OpenStation Max успешно установлена!",
                FontSize = 18,
                FontWeight = FontWeights.Bold,
                Foreground = new SolidColorBrush(Color.FromRgb(52, 211, 153))
            };
            hTextStack.Children.Add(hTitle);

            var hDesc = new TextBlock
            {
                Text = "Системные разделы прошиты, загрузчик U-Boot разблокирован, защита от бутлупа активирована.",
                FontSize = 12.5,
                Foreground = new SolidColorBrush(Color.FromRgb(209, 250, 229)),
                Margin = new Thickness(0, 4, 0, 0)
            };
            hTextStack.Children.Add(hDesc);
            hStack.Children.Add(hTextStack);
            headerCard.Child = hStack;

            Grid.SetRow(headerCard, 0);
            grid.Children.Add(headerCard);

            // Instructions Box
            var instrCard = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(160, 18, 24, 38)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(40, 255, 255, 255)),
                BorderThickness = new Thickness(1.2),
                CornerRadius = new CornerRadius(18),
                Padding = new Thickness(22, 16, 22, 16),
                Margin = new Thickness(0, 0, 0, 14)
            };
            var iStack = new StackPanel();

            var iTitle = new TextBlock
            {
                Text = "Что необходимо сделать прямо сейчас (Пошагово):",
                FontSize = 14,
                FontWeight = FontWeights.Bold,
                Foreground = new SolidColorBrush(Color.FromRgb(240, 243, 248)),
                Margin = new Thickness(0, 0, 0, 12)
            };
            iStack.Children.Add(iTitle);

            iStack.Children.Add(CreateStepInstruction(
                "1",
                "ОБЯЗАТЕЛЬНО СНИМИТЕ ПЕРЕМЫЧКУ С 6 ПИНА",
                "Если оставить скрепку/перемычку на 6 пине, колонка при включении питания снова войдёт в режим прошивки BootROM вместо нормальной загрузки Android."));

            iStack.Children.Add(CreateStepInstruction(
                "2",
                "ПЕРЕЗАГРУЗИТЕ ПИТАНИЕ 19V",
                "Отключите круглый штекер питания 19V на 3 секунды и вставьте обратно. Провод USB от ПК можно отключить."));

            iStack.Children.Add(CreateStepInstruction(
                "3",
                "ДОЖДИТЕСЬ ПЕРВОГО СТАРТА (~60-90 СЕКУНД)",
                "Первый запуск после форматирования /data занимает около минуты. На подключенном к HDMI телевизоре появится стильный лаунчер со сферой Nexus Anomaly."));

            iStack.Children.Add(CreateStepInstruction(
                "4",
                "ОТКРОЙТЕ ВЕБ-ПУЛЬТ УПРАВЛЕНИЯ",
                "Управляйте Станцией через браузер с любого телефона или ноутбука в домашней сети по адресу http://192.168.0.20:8080 или station-max.local:8080."));

            instrCard.Child = iStack;
            Grid.SetRow(instrCard, 1);
            grid.Children.Add(instrCard);

            // Action Buttons
            var btnGrid = new Grid();
            btnGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.2, GridUnitType.Star) });
            btnGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            btnGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.1, GridUnitType.Star) });
            btnGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.7, GridUnitType.Star) });

            _btnStep4WebUi = CreateActionButton("🌐 Открыть Веб-пульт", Color.FromRgb(0, 212, 255), Color.FromRgb(10, 30, 48));
            _btnStep4WebUi.Height = 44;
            _btnStep4WebUi.Margin = new Thickness(0, 0, 6, 0);
            _btnStep4WebUi.Click += (s, e) => OpenWebUi();
            Grid.SetColumn(_btnStep4WebUi, 0);
            btnGrid.Children.Add(_btnStep4WebUi);

            _btnStep4TvScreen = CreateActionButton("📺 Скриншот ТВ (ADB)", Color.FromRgb(168, 85, 247), Color.FromRgb(36, 20, 52));
            _btnStep4TvScreen.Height = 44;
            _btnStep4TvScreen.Margin = new Thickness(4, 0, 4, 0);
            _btnStep4TvScreen.Click += (s, e) => CaptureTvScreenshot();
            Grid.SetColumn(_btnStep4TvScreen, 1);
            btnGrid.Children.Add(_btnStep4TvScreen);

            _btnStep4RestartWizard = CreateActionButton("🔄 Прошить другую Станцию", Color.FromRgb(200, 210, 225), Color.FromRgb(26, 32, 44));
            _btnStep4RestartWizard.Height = 44;
            _btnStep4RestartWizard.Margin = new Thickness(4, 0, 4, 0);
            _btnStep4RestartWizard.Click += (s, e) => GoToStep(1);
            Grid.SetColumn(_btnStep4RestartWizard, 2);
            btnGrid.Children.Add(_btnStep4RestartWizard);

            _btnStep4Exit = CreateActionButton("✕ Выход", Color.FromRgb(248, 113, 113), Color.FromRgb(40, 20, 24));
            _btnStep4Exit.Height = 44;
            _btnStep4Exit.Margin = new Thickness(6, 0, 0, 0);
            _btnStep4Exit.Click += (s, e) => Application.Current.Shutdown();
            Grid.SetColumn(_btnStep4Exit, 3);
            btnGrid.Children.Add(_btnStep4Exit);

            Grid.SetRow(btnGrid, 2);
            grid.Children.Add(btnGrid);

            return grid;
        }

        private FrameworkElement CreateStepInstruction(string num, string title, string desc)
        {
            var border = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(80, 24, 32, 48)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(30, 255, 255, 255)),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(12),
                Padding = new Thickness(12, 8, 12, 8),
                Margin = new Thickness(0, 0, 0, 8)
            };
            var g = new Grid();
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            var nCircle = new Border
            {
                Width = 24,
                Height = 24,
                CornerRadius = new CornerRadius(12),
                Background = new SolidColorBrush(Color.FromRgb(0, 212, 255)),
                Margin = new Thickness(0, 2, 10, 0),
                VerticalAlignment = VerticalAlignment.Top
            };
            nCircle.Child = new TextBlock
            {
                Text = num,
                FontSize = 12,
                FontWeight = FontWeights.Bold,
                Foreground = new SolidColorBrush(Color.FromRgb(10, 20, 30)),
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center
            };
            Grid.SetColumn(nCircle, 0);
            g.Children.Add(nCircle);

            var sp = new StackPanel();
            var t = new TextBlock
            {
                Text = title,
                FontSize = 12.5,
                FontWeight = FontWeights.Bold,
                Foreground = new SolidColorBrush(Color.FromRgb(240, 243, 248))
            };
            sp.Children.Add(t);

            var d = new TextBlock
            {
                Text = desc,
                FontSize = 11.5,
                Foreground = new SolidColorBrush(Color.FromRgb(148, 163, 184)),
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 2, 0, 0)
            };
            sp.Children.Add(d);
            Grid.SetColumn(sp, 1);
            g.Children.Add(sp);

            border.Child = g;
            return border;
        }

        // ==========================================
        // 6. LOG DRAWER (COLLAPSIBLE)
        // ==========================================
        private Border BuildLogDrawer()
        {
            var drawer = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(240, 10, 13, 20)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(40, 255, 255, 255)),
                BorderThickness = new Thickness(0, 1, 0, 0),
                Height = 140,
                Visibility = Visibility.Collapsed
            };

            var grid = new Grid();
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

            var logHeader = new Grid { Margin = new Thickness(16, 6, 16, 6) };
            logHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            logHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var lbl = new TextBlock
            {
                Text = "Журнал операций (Console Output):",
                FontSize = 11.5,
                FontWeight = FontWeights.SemiBold,
                Foreground = new SolidColorBrush(Color.FromRgb(148, 163, 184)),
                VerticalAlignment = VerticalAlignment.Center
            };
            Grid.SetColumn(lbl, 0);
            logHeader.Children.Add(lbl);

            var btns = new StackPanel { Orientation = Orientation.Horizontal };
            var btnCopy = CreateSmallButton("Скопировать журнал", Color.FromRgb(30, 36, 48), Color.FromRgb(200, 210, 225));
            btnCopy.Margin = new Thickness(0, 0, 6, 0);
            btnCopy.Click += (s, e) =>
            {
                try
                {
                    Clipboard.SetText(_logBox.Text);
                    MessageBox.Show("Лог скопирован в буфер обмена!", "Готово", MessageBoxButton.OK, MessageBoxImage.Information);
                }
                catch { }
            };
            btns.Children.Add(btnCopy);

            var btnClear = CreateSmallButton("Очистить", Color.FromRgb(30, 36, 48), Color.FromRgb(200, 210, 225));
            btnClear.Click += (s, e) => _logBox.Clear();
            btns.Children.Add(btnClear);

            Grid.SetColumn(btns, 1);
            logHeader.Children.Add(btns);
            Grid.SetRow(logHeader, 0);
            grid.Children.Add(logHeader);

            _logBox = new TextBox
            {
                IsReadOnly = true,
                TextWrapping = TextWrapping.Wrap,
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                Background = new SolidColorBrush(Color.FromRgb(8, 10, 15)),
                Foreground = new SolidColorBrush(Color.FromRgb(180, 190, 205)),
                BorderThickness = new Thickness(0),
                FontFamily = new FontFamily("Consolas, Courier New, monospace"),
                FontSize = 11,
                Padding = new Thickness(16, 4, 16, 8)
            };
            Grid.SetRow(_logBox, 1);
            grid.Children.Add(_logBox);

            drawer.Child = grid;
            return drawer;
        }

        private void ToggleLogDrawer()
        {
            _isLogVisible = !_isLogVisible;
            _logDrawer.Visibility = _isLogVisible ? Visibility.Visible : Visibility.Collapsed;
            _btnToggleLog.Content = _isLogVisible ? "📋 Скрыть лог ▼" : "📋 Показать лог ▲";
        }

        // ==========================================
        // 7. BOTTOM NAVIGATION BAR
        // ==========================================
        private FrameworkElement BuildBottomNav()
        {
            var nav = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(180, 14, 18, 28)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(40, 255, 255, 255)),
                BorderThickness = new Thickness(0, 1, 0, 0),
                Padding = new Thickness(24, 12, 24, 12)
            };
            Grid.SetRow(nav, 4);

            var grid = new Grid();
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); // Left: Back button
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); // Left 2: Log button
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) }); // Center: Device Status Pill
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); // Right: Next / Finish button

            _btnNavBack = CreateActionButton("◀ Назад", Color.FromRgb(200, 210, 225), Color.FromRgb(26, 32, 44));
            _btnNavBack.Padding = new Thickness(18, 8, 18, 8);
            _btnNavBack.IsEnabled = false;
            _btnNavBack.Click += (s, e) => NavBack();
            Grid.SetColumn(_btnNavBack, 0);
            grid.Children.Add(_btnNavBack);

            _btnToggleLog = CreateSmallButton("📋 Показать лог ▲", Color.FromRgb(24, 30, 42), Color.FromRgb(148, 163, 184));
            _btnToggleLog.Margin = new Thickness(10, 0, 0, 0);
            _btnToggleLog.Click += (s, e) => ToggleLogDrawer();
            Grid.SetColumn(_btnToggleLog, 1);
            grid.Children.Add(_btnToggleLog);

            // Center: Device Status Mini-Pill
            _bottomDevicePill = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(100, 18, 24, 36)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(40, 255, 255, 255)),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(16),
                Padding = new Thickness(14, 6, 14, 6),
                HorizontalAlignment = HorizontalAlignment.Center
            };
            var pillStack = new StackPanel { Orientation = Orientation.Horizontal };
            _bottomDeviceOrb = new System.Windows.Shapes.Ellipse
            {
                Width = 10,
                Height = 10,
                Fill = new SolidColorBrush(Color.FromRgb(245, 158, 11)),
                Margin = new Thickness(0, 0, 8, 0),
                VerticalAlignment = VerticalAlignment.Center
            };
            pillStack.Children.Add(_bottomDeviceOrb);

            _bottomDeviceText = new TextBlock
            {
                Text = "Ожидание подключения Станции...",
                FontSize = 11.5,
                Foreground = new SolidColorBrush(Color.FromRgb(200, 210, 225)),
                VerticalAlignment = VerticalAlignment.Center
            };
            pillStack.Children.Add(_bottomDeviceText);
            _bottomDevicePill.Child = pillStack;
            Grid.SetColumn(_bottomDevicePill, 2);
            grid.Children.Add(_bottomDevicePill);

            // Right: Next Button
            _btnNavNext = CreateActionButton("Далее: Подключение кабеля ➔", Color.FromRgb(0, 212, 255), Color.FromRgb(10, 30, 48));
            _btnNavNext.Padding = new Thickness(20, 8, 20, 8);
            _btnNavNext.Click += (s, e) => NavNext();
            Grid.SetColumn(_btnNavNext, 3);
            grid.Children.Add(_btnNavNext);

            nav.Child = grid;
            return nav;
        }

        // ==========================================
        // WIZARD NAVIGATION LOGIC
        // ==========================================
        private void GoToStep(int step)
        {
            if (_isFlashing) return;
            if (step < 1 || step > 4) return;
            _currentStep = step;
            UpdateStepView();
        }

        private void NavBack()
        {
            if (_isFlashing) return;
            if (_currentStep > 1) GoToStep(_currentStep - 1);
        }

        private void NavNext()
        {
            if (_isFlashing) return;
            if (_currentStep == 1)
            {
                GoToStep(2);
            }
            else if (_currentStep == 2)
            {
                GoToStep(3);
            }
            else if (_currentStep == 3)
            {
                TriggerFlashExecution();
            }
            else if (_currentStep == 4)
            {
                Application.Current.Shutdown();
            }
        }

        private void UpdateStepView()
        {
            _step1View.Visibility = (_currentStep == 1) ? Visibility.Visible : Visibility.Collapsed;
            _step2View.Visibility = (_currentStep == 2) ? Visibility.Visible : Visibility.Collapsed;
            _step3View.Visibility = (_currentStep == 3) ? Visibility.Visible : Visibility.Collapsed;
            _step4View.Visibility = (_currentStep == 4) ? Visibility.Visible : Visibility.Collapsed;

            _btnNavBack.IsEnabled = (_currentStep > 1 && !_isFlashing);

            if (_currentStep == 1)
            {
                _btnNavNext.Content = "Далее: Подключение кабеля ➔";
                _btnNavNext.Style = CreateAppleGlassButtonStyle(Color.FromRgb(0, 212, 255), Color.FromRgb(10, 30, 48), 20, true);
            }
            else if (_currentStep == 2)
            {
                _btnNavNext.Content = "Далее: Выбор прошивки ➔";
                _btnNavNext.Style = CreateAppleGlassButtonStyle(Color.FromRgb(0, 212, 255), Color.FromRgb(10, 30, 48), 20, true);
            }
            else if (_currentStep == 3)
            {
                _btnNavNext.Content = "⚡ Начать прошивку";
                _btnNavNext.Style = CreateAppleGlassButtonStyle(Color.FromRgb(16, 185, 129), Color.FromRgb(10, 36, 24), 20, true);
            }
            else if (_currentStep == 4)
            {
                _btnNavNext.Content = "✓ Завершить работу";
                _btnNavNext.Style = CreateAppleGlassButtonStyle(Color.FromRgb(16, 185, 129), Color.FromRgb(10, 36, 24), 20, true);
            }

            UpdateStepperUI();
        }

        // ==========================================
        // HELPERS: SELECTABLE CARDS & BUTTONS
        // ==========================================
        private Border CreateSelectableCard(string title, string subtitle, string desc, string badgeText, Color accentColor, bool isSelected)
        {
            var card = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(160, 16, 22, 34)),
                BorderBrush = new SolidColorBrush(isSelected ? accentColor : Color.FromArgb(40, 255, 255, 255)),
                BorderThickness = new Thickness(isSelected ? 2 : 1),
                CornerRadius = new CornerRadius(16),
                Padding = new Thickness(14, 12, 14, 12),
                Margin = new Thickness(4, 4, 4, 4),
                Cursor = Cursors.Hand
            };

            var stack = new StackPanel();

            var topRow = new Grid();
            topRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            topRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var tBlock = new TextBlock
            {
                Text = title,
                FontSize = 13.5,
                FontWeight = FontWeights.Bold,
                Foreground = new SolidColorBrush(accentColor)
            };
            Grid.SetColumn(tBlock, 0);
            topRow.Children.Add(tBlock);

            var bBadge = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(35, accentColor.R, accentColor.G, accentColor.B)),
                BorderBrush = new SolidColorBrush(accentColor),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(10),
                Padding = new Thickness(8, 2, 8, 2)
            };
            bBadge.Child = new TextBlock
            {
                Text = badgeText,
                FontSize = 10,
                FontWeight = FontWeights.SemiBold,
                Foreground = new SolidColorBrush(accentColor)
            };
            Grid.SetColumn(bBadge, 1);
            topRow.Children.Add(bBadge);
            stack.Children.Add(topRow);

            var subBlock = new TextBlock
            {
                Text = subtitle,
                FontSize = 11,
                FontWeight = FontWeights.SemiBold,
                Foreground = new SolidColorBrush(Color.FromRgb(200, 210, 225)),
                Margin = new Thickness(0, 4, 0, 0)
            };
            stack.Children.Add(subBlock);

            var dBlock = new TextBlock
            {
                Text = desc,
                FontSize = 11,
                Foreground = new SolidColorBrush(Color.FromRgb(148, 163, 184)),
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 4, 0, 0),
                LineHeight = 15
            };
            stack.Children.Add(dBlock);

            card.Child = stack;
            return card;
        }

        private void SetCardSelected(Border card, bool isSelected, Color accentColor)
        {
            card.BorderBrush = new SolidColorBrush(isSelected ? accentColor : Color.FromArgb(40, 255, 255, 255));
            card.BorderThickness = new Thickness(isSelected ? 2 : 1);
            card.Background = new SolidColorBrush(isSelected
                ? Color.FromArgb(200, (byte)Math.Min(255, accentColor.R / 6 + 14), (byte)Math.Min(255, accentColor.G / 6 + 18), (byte)Math.Min(255, accentColor.B / 6 + 28))
                : Color.FromArgb(160, 16, 22, 34));
        }

        private Border CreatePrereqItem(string title, out TextBlock valBlock)
        {
            var border = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(120, 20, 26, 40)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(35, 255, 255, 255)),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(10),
                Padding = new Thickness(10, 6, 10, 6),
                Margin = new Thickness(3, 0, 3, 0)
            };
            var sp = new StackPanel { Orientation = Orientation.Horizontal };
            var lbl = new TextBlock
            {
                Text = title + ": ",
                FontSize = 11,
                Foreground = new SolidColorBrush(Color.FromRgb(148, 163, 184)),
                VerticalAlignment = VerticalAlignment.Center
            };
            sp.Children.Add(lbl);

            valBlock = new TextBlock
            {
                Text = "Проверка...",
                FontSize = 11,
                FontWeight = FontWeights.SemiBold,
                Foreground = new SolidColorBrush(Color.FromRgb(245, 158, 11)),
                VerticalAlignment = VerticalAlignment.Center
            };
            sp.Children.Add(valBlock);
            border.Child = sp;
            return border;
        }

        private static Style CreateAppleGlassButtonStyle(Color fg, Color bg, int cornerRadius, bool isAccent)
        {
            var style = new Style(typeof(Button));
            var template = new ControlTemplate(typeof(Button));
            var borderFactory = new FrameworkElementFactory(typeof(Border));
            borderFactory.Name = "border";
            borderFactory.SetValue(Border.CornerRadiusProperty, new CornerRadius(cornerRadius));
            borderFactory.SetValue(Border.BackgroundProperty, new SolidColorBrush(bg));
            borderFactory.SetValue(Border.BorderBrushProperty, new SolidColorBrush(isAccent ? Color.FromArgb(140, fg.R, fg.G, fg.B) : Color.FromArgb(45, 255, 255, 255)));
            borderFactory.SetValue(Border.BorderThicknessProperty, new Thickness(1.2));

            var contentPresenter = new FrameworkElementFactory(typeof(ContentPresenter));
            contentPresenter.SetValue(ContentPresenter.HorizontalAlignmentProperty, HorizontalAlignment.Center);
            contentPresenter.SetValue(ContentPresenter.VerticalAlignmentProperty, VerticalAlignment.Center);
            borderFactory.AppendChild(contentPresenter);

            template.VisualTree = borderFactory;

            var hoverTrigger = new Trigger { Property = UIElement.IsMouseOverProperty, Value = true };
            Color hoverBg = Color.FromArgb(
                (byte)Math.Min(255, bg.A + 45),
                (byte)Math.Min(255, bg.R + 25),
                (byte)Math.Min(255, bg.G + 25),
                (byte)Math.Min(255, bg.B + 25));
            hoverTrigger.Setters.Add(new Setter(Border.BackgroundProperty, new SolidColorBrush(hoverBg), "border"));
            hoverTrigger.Setters.Add(new Setter(Border.BorderBrushProperty, new SolidColorBrush(fg), "border"));
            template.Triggers.Add(hoverTrigger);

            style.Setters.Add(new Setter(Button.TemplateProperty, template));
            style.Setters.Add(new Setter(Control.ForegroundProperty, new SolidColorBrush(fg)));
            style.Setters.Add(new Setter(FrameworkElement.CursorProperty, Cursors.Hand));
            return style;
        }

        private Button CreateSmallButton(string text, Color bg, Color fg)
        {
            var btn = new Button
            {
                Content = text,
                Padding = new Thickness(10, 4, 10, 4),
                FontSize = 11,
                Cursor = Cursors.Hand,
                Style = CreateAppleGlassButtonStyle(fg, bg, 999, false)
            };
            return btn;
        }

        private Button CreateActionButton(string text, Color fg, Color bg)
        {
            var btn = new Button
            {
                Content = text,
                Padding = new Thickness(14, 8, 14, 8),
                FontSize = 12,
                FontWeight = FontWeights.SemiBold,
                Cursor = Cursors.Hand,
                Style = CreateAppleGlassButtonStyle(fg, bg, 14, true)
            };
            return btn;
        }

        // ==========================================
        // 8. MODAL SLIDE-OVER OVERLAY
        // ==========================================
        private Border BuildModalOverlay()
        {
            var overlay = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(190, 6, 8, 14)),
                Visibility = Visibility.Collapsed
            };
            overlay.MouseDown += (s, e) =>
            {
                if (e.OriginalSource == overlay) overlay.Visibility = Visibility.Collapsed;
            };
            Grid.SetRow(overlay, 0);
            Grid.SetRowSpan(overlay, 5);
            Panel.SetZIndex(overlay, 9999);

            _modalCard = new Border
            {
                Width = 720,
                MaxHeight = 580,
                Background = new SolidColorBrush(Color.FromRgb(16, 22, 34)),
                BorderBrush = new SolidColorBrush(Color.FromRgb(0, 212, 255)),
                BorderThickness = new Thickness(1.5),
                CornerRadius = new CornerRadius(20),
                Padding = new Thickness(24),
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center
            };

            var stack = new Grid();
            stack.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            stack.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
            stack.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

            var head = new Grid { Margin = new Thickness(0, 0, 0, 14) };
            head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            _modalTitle = new TextBlock
            {
                FontSize = 16,
                FontWeight = FontWeights.Bold,
                Foreground = new SolidColorBrush(Color.FromRgb(0, 212, 255)),
                VerticalAlignment = VerticalAlignment.Center
            };
            Grid.SetColumn(_modalTitle, 0);
            head.Children.Add(_modalTitle);

            var btnClose = CreateSmallButton("✕ Закрыть", Color.FromRgb(30, 36, 48), Color.FromRgb(200, 210, 225));
            btnClose.Click += (s, e) => _modalOverlay.Visibility = Visibility.Collapsed;
            Grid.SetColumn(btnClose, 1);
            head.Children.Add(btnClose);
            Grid.SetRow(head, 0);
            stack.Children.Add(head);

            var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
            _modalBody = new TextBlock
            {
                FontSize = 12,
                LineHeight = 18,
                Foreground = new SolidColorBrush(Color.FromRgb(226, 232, 240)),
                FontFamily = new FontFamily("Consolas, Segoe UI, monospace"),
                TextWrapping = TextWrapping.Wrap
            };
            scroll.Content = _modalBody;
            Grid.SetRow(scroll, 1);
            stack.Children.Add(scroll);

            var foot = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 14, 0, 0) };
            var btnOk = CreateActionButton("Понятно", Color.FromRgb(0, 212, 255), Color.FromRgb(10, 30, 48));
            btnOk.Click += (s, e) => _modalOverlay.Visibility = Visibility.Collapsed;
            foot.Children.Add(btnOk);
            Grid.SetRow(foot, 2);
            stack.Children.Add(foot);

            _modalCard.Child = stack;
            overlay.Child = _modalCard;
            return overlay;
        }

        private void ShowModal(string title, string text)
        {
            _modalTitle.Text = title;
            _modalBody.Text = text;
            _modalOverlay.Visibility = Visibility.Visible;
        }

        private string GetGuideHelpText()
        {
            return
                "• КАК РАБОТАЕТ OPENSTATION MAX:\n" +
                "  OpenStation Max полностью заменяет прошивку на открытую систему (system.img + vendor.img),\n" +
                "  а системный демон openstationd работает 100% автономно на процессоре Станции.\n\n" +
                "• ЧТО РАБОТАЕТ ПОСЛЕ ПРОШИВКИ:\n" +
                "  ✓ Все 5 акустических динамиков (вуфер, твитеры, пассивные излучатели) и усилитель 65W;\n" +
                "  ✓ LED-экран (часы, погода, эквалайзер, визуализатор музыки);\n" +
                "  ✓ HDMI 4K ТВ-лаунчер со стилем Apple Glass и 3D сферой Nexus Anomaly;\n" +
                "  ✓ Голосовой ассистент Алиса (Gemini Live / offline) и Яндекс Музыка (Моя волна);\n" +
                "  ✓ Автономный веб-пульт :8080 для управления с телефона или ПК.\n\n" +
                "• БЫСТРАЯ ИНСТРУКЦИЯ:\n" +
                "  1. Замкните пины 6 и 3 скрепкой, воткните кабель USB в ПК и подключите 19V питание.\n" +
                "  2. Когда индикатор устройства загорится зелёным, нажмите «⚡ Чистая сервисная прошивка».\n" +
                "  3. После прошивки снимите скрепку с 6 пина и переподключите 19V питание на 3 секунды.";
        }

        private string GetBootloopHelpText()
        {
            return
                "• ПРИЧИНЫ БУТЛУПА В СТАРЫХ СКРИПТАХ:\n" +
                "  1. Вызов 'amlmmc enc': эта команда недопустима на Amlogic G12 и аварийно роняла USB-канал (код -1367).\n" +
                "     В результате команды 'setenv avb2 0' и 'saveenv' даже не выполнялись!\n" +
                "  2. Отсутствие записи раздела misc: стоковый загрузчик требовал AVB dm-verity и SELinux enforcing.\n" +
                "     При попытке загрузить сторонний system/vendor ядро аварийно перезагружалось.\n\n" +
                "• КАК ЭТО ИСПРАВЛЕНО В ВЕРСИИ 1.3.5:\n" +
                "  ✓ 'amlmmc enc' полностью удалена из всех скриптов и командного конвейера.\n" +
                "  ✓ В раздел misc прошивается uboot-command с аргументами:\n" +
                "    androidboot.selinux=permissive androidboot.verifiedbootstate=orange androidboot.vbmeta.skip=1\n" +
                "  ✓ В U-Boot надёжно сохраняются: lock=10000000, avb2=0, silent=0.\n" +
                "  ✓ Для ревизии 00053 автоматически выбираются загрузчик aml_00053.img и дерево rev375f.\n" +
                "  Колонка гарантированно запускается в Android 9 без циклических перезагрузок!";
        }

        // ==========================================
        // DEVICE POLLING & PREREQUISITES
        // ==========================================
        private void StartDevicePolling()
        {
            _pollTimer = new DispatcherTimer();
            _pollTimer.Interval = TimeSpan.FromMilliseconds(1500);
            _pollTimer.Tick += (s, e) => CheckDeviceStatus();
            _pollTimer.Start();
        }

        private void CheckDeviceStatus()
        {
            if (_isFlashing) return;

            ThreadPool.QueueUserWorkItem(_ =>
            {
                bool connected = false;
                bool driverError = false;
                string deviceName = "";
                string errDetail = "";
                try
                {
                    using (var searcher = new ManagementObjectSearcher("SELECT * FROM Win32_PnPEntity WHERE DeviceID LIKE '%VID_1B8E&PID_C003%'"))
                    {
                        foreach (var obj in searcher.Get())
                        {
                            connected = true;
                            deviceName = (obj["Name"] ?? obj["Caption"] ?? "Amlogic WorldCup Device").ToString();
                            var codeObj = obj["ConfigManagerErrorCode"];
                            int code = 0;
                            if (codeObj != null) int.TryParse(codeObj.ToString(), out code);
                            if (code != 0)
                            {
                                driverError = true;
                                errDetail = "Код ошибки Windows " + code + " (драйвер не установлен или заблокирован)";
                            }
                            break;
                        }
                    }
                }
                catch { }

                bool adbConnected = false;
                string adbSerial = "";
                if (!connected)
                {
                    try
                    {
                        string adb = FindAdbExe();
                        var psi = new ProcessStartInfo(adb, "devices")
                        {
                            UseShellExecute = false,
                            CreateNoWindow = true,
                            RedirectStandardOutput = true
                        };
                        using (var p = Process.Start(psi))
                        {
                            string outStr = p.StandardOutput.ReadToEnd();
                            p.WaitForExit(600);
                            var lines = outStr.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries);
                            foreach (var line in lines)
                            {
                                if (line.StartsWith("List") || line.StartsWith("*")) continue;
                                if (line.Contains("\tdevice") || line.EndsWith("device"))
                                {
                                    adbConnected = true;
                                    adbSerial = line.Split('\t', ' ')[0].Trim();
                                    break;
                                }
                            }
                        }
                    }
                    catch { }
                }

                Dispatcher.Invoke(() =>
                {
                    _isDeviceConnected = connected;
                    _isAdbConnected = adbConnected;
                    _driverError = driverError;
                    _driverErrDetail = errDetail;
                    _deviceName = deviceName;
                    _adbSerial = adbSerial;

                    UpdateDeviceStatusUI();
                });
            });
        }

        private void UpdateDeviceStatusUI()
        {
            if (_isDeviceConnected)
            {
                _step2BtnAdbReboot.Visibility = Visibility.Collapsed;

                if (_driverError)
                {
                    // Driver missing
                    _bottomDeviceOrb.Fill = new SolidColorBrush(Color.FromRgb(245, 158, 11));
                    _bottomDeviceText.Text = "⚠️ Станция подключена, но нужен драйвер WorldCup!";
                    _bottomDeviceText.Foreground = new SolidColorBrush(Color.FromRgb(245, 158, 11));

                    _step2StatusOrb.Fill = new SolidColorBrush(Color.FromRgb(245, 158, 11));
                    _step2StatusTitle.Text = "⚠️ Устройство Amlogic подключено без драйвера!";
                    _step2StatusTitle.Foreground = new SolidColorBrush(Color.FromRgb(245, 158, 11));
                    _step2StatusDesc.Text = _driverErrDetail + ". Нажмите кнопку «🔧 Установить драйвер WorldCup» ниже.";
                    _step2StatusBadge.BorderBrush = new SolidColorBrush(Color.FromRgb(245, 158, 11));
                }
                else
                {
                    // WorldCup Ready
                    _bottomDeviceOrb.Fill = new SolidColorBrush(Color.FromRgb(16, 185, 129));
                    _bottomDeviceText.Text = "● Станция Макс подключена (WorldCup USB Boot)";
                    _bottomDeviceText.Foreground = new SolidColorBrush(Color.FromRgb(52, 211, 153));

                    _step2StatusOrb.Fill = new SolidColorBrush(Color.FromRgb(16, 185, 129));
                    _step2StatusTitle.Text = "● Станция Макс готова к прошивке!";
                    _step2StatusTitle.Foreground = new SolidColorBrush(Color.FromRgb(52, 211, 153));
                    _step2StatusDesc.Text = _deviceName + " в режиме USB Boot. Можно переходить к Шагу 3!";
                    _step2StatusBadge.BorderBrush = new SolidColorBrush(Color.FromRgb(16, 185, 129));
                }
            }
            else if (_isAdbConnected)
            {
                _step2BtnAdbReboot.Visibility = Visibility.Visible;

                _bottomDeviceOrb.Fill = new SolidColorBrush(Color.FromRgb(56, 189, 248));
                _bottomDeviceText.Text = "● Станция подключена по ADB (" + _adbSerial + ")";
                _bottomDeviceText.Foreground = new SolidColorBrush(Color.FromRgb(56, 189, 248));

                _step2StatusOrb.Fill = new SolidColorBrush(Color.FromRgb(56, 189, 248));
                _step2StatusTitle.Text = "● Станция обнаружена по ADB (Android)";
                _step2StatusTitle.Foreground = new SolidColorBrush(Color.FromRgb(56, 189, 248));
                _step2StatusDesc.Text = "Серийный номер: " + _adbSerial + ". Доступна быстрая прошивка или кнопка перехода в WorldCup.";
                _step2StatusBadge.BorderBrush = new SolidColorBrush(Color.FromRgb(56, 189, 248));
            }
            else
            {
                _step2BtnAdbReboot.Visibility = Visibility.Collapsed;

                _bottomDeviceOrb.Fill = new SolidColorBrush(Color.FromRgb(245, 158, 11));
                _bottomDeviceText.Text = "◌ Ожидание подключения Станции Макс...";
                _bottomDeviceText.Foreground = new SolidColorBrush(Color.FromRgb(180, 190, 205));

                _step2StatusOrb.Fill = new SolidColorBrush(Color.FromRgb(245, 158, 11));
                _step2StatusTitle.Text = "Ожидание подключения...";
                _step2StatusTitle.Foreground = new SolidColorBrush(Color.FromRgb(245, 158, 11));
                _step2StatusDesc.Text = "Замкните пины 6 и 3, воткните USB в ПК и подключите 19V питание.";
                _step2StatusBadge.BorderBrush = new SolidColorBrush(Color.FromArgb(50, 255, 255, 255));
            }
        }

        private void CheckPrerequisites()
        {
            ThreadPool.QueueUserWorkItem(_ =>
            {
                try
                {
                    bool scriptReady = File.Exists(Path.Combine(_baseDir, "scripts", "flash_service_port.ps1"));
                    if (!scriptReady)
                    {
                        string recheckBase = ResolveBaseDirectory();
                        if (recheckBase != _baseDir && File.Exists(Path.Combine(recheckBase, "scripts", "flash_service_port.ps1")))
                        {
                            _baseDir = recheckBase;
                            _serviceFlashDir = Path.Combine(_baseDir, "service_flash");
                            _romDir = Path.Combine(_baseDir, "rom");
                            scriptReady = true;
                        }
                    }

                    // 1. U-Boot loaders
                    bool aml8 = File.Exists(Path.Combine(_serviceFlashDir, "aml.img"));
                    bool aml53 = File.Exists(Path.Combine(_serviceFlashDir, "aml_00053.img"));

                    // 2. ROM Images
                    bool vbm = File.Exists(Path.Combine(_romDir, "vbmeta.img"));
                    bool ven = File.Exists(Path.Combine(_romDir, "vendor.img"));
                    bool sys = File.Exists(Path.Combine(_romDir, "system.img"));
                    bool misc = File.Exists(Path.Combine(_romDir, "misc.img")) || File.Exists(Path.Combine(_serviceFlashDir, "misc.img"));
                    bool allRom = vbm && ven && sys && misc;

                    // 3. Driver
                    bool drvInstalled = false;
                    try
                    {
                        string windir = Environment.GetFolderPath(Environment.SpecialFolder.Windows);
                        drvInstalled = File.Exists(Path.Combine(windir, "System32", "drivers", "libusb0.sys"));
                    }
                    catch { }

                    // 4. Python
                    string py = FindPythonExe();

                    Dispatcher.Invoke(() =>
                    {
                        if (_valUboot != null)
                        {
                            if (aml8 && aml53)
                            {
                                _valUboot.Text = "Готов (0008, 00053)";
                                _valUboot.Foreground = new SolidColorBrush(Color.FromRgb(52, 211, 153));
                            }
                            else if (aml8 || aml53)
                            {
                                _valUboot.Text = aml8 ? "0008 готов" : "00053 готов";
                                _valUboot.Foreground = new SolidColorBrush(Color.FromRgb(245, 158, 11));
                            }
                            else
                            {
                                _valUboot.Text = scriptReady ? "В папке service_flash" : "Архив не распакован!";
                                _valUboot.Foreground = new SolidColorBrush(Color.FromRgb(239, 68, 68));
                            }
                        }

                        if (_valRom != null)
                        {
                            if (allRom)
                            {
                                _valRom.Text = "Готовы (4 раздела)";
                                _valRom.Foreground = new SolidColorBrush(Color.FromRgb(52, 211, 153));
                            }
                            else if (!scriptReady)
                            {
                                _valRom.Text = "Архив не распакован!";
                                _valRom.Foreground = new SolidColorBrush(Color.FromRgb(239, 68, 68));
                            }
                            else
                            {
                                _valRom.Text = "Авто-загрузка ZIP";
                                _valRom.Foreground = new SolidColorBrush(Color.FromRgb(56, 189, 248));
                            }
                        }

                        if (_valDriver != null)
                        {
                            if (drvInstalled)
                            {
                                _valDriver.Text = "Установлен (libusb0)";
                                _valDriver.Foreground = new SolidColorBrush(Color.FromRgb(52, 211, 153));
                            }
                            else
                            {
                                _valDriver.Text = "Не установлен";
                                _valDriver.Foreground = new SolidColorBrush(Color.FromRgb(245, 158, 11));
                            }
                        }

                        if (_valPython != null)
                        {
                            if (!string.IsNullOrEmpty(py))
                            {
                                _valPython.Text = "Готов (" + Path.GetFileName(py) + ")";
                                _valPython.Foreground = new SolidColorBrush(Color.FromRgb(52, 211, 153));
                            }
                            else
                            {
                                _valPython.Text = "Авто-скачивание";
                                _valPython.Foreground = new SolidColorBrush(Color.FromRgb(56, 189, 248));
                            }
                        }
                    });
                }
                catch { }
            });
        }

        private void SetControlsEnabled(bool enabled)
        {
            _btnNavBack.IsEnabled = (enabled && _currentStep > 1);
            _btnNavNext.IsEnabled = enabled;
            _btnStartFlashBig.IsEnabled = enabled;
            _cardModeClean.IsEnabled = enabled;
            _cardModeKeep.IsEnabled = enabled;
            _cardModeAfterUbt.IsEnabled = enabled;
            _cardModeAdb.IsEnabled = enabled;
            _cardRevAuto.IsEnabled = enabled;
            _cardRev0008.IsEnabled = enabled;
            _cardRev0053.IsEnabled = enabled;
        }

        // ==========================================
        // ACTIONS & FLASH RUNNER
        // ==========================================
        private void StartFlash(bool wipeData)
        {
            if (_isFlashing) return;

            string rev = _selectedRevision;
            string modeName = wipeData ? "Чистая сервисная прошивка (Wipe + Unbrick)" : "Обновление системы (/data сохранён)";
            var res = MessageBox.Show(
                "Начать операцию: " + modeName + "?\n\n" +
                "Ревизия станции: " + rev + "\n\n" +
                "Проверьте перед стартом:\n" +
                "1) Перемычка между пинами 6 (BOOT) и 3 (GND) установлена;\n" +
                "2) Кабель Type-C подключен к ПК (пины 1, 2, 3);\n" +
                "3) Питание 19V включено.",
                "Подтверждение прошивки",
                MessageBoxButton.YesNo,
                MessageBoxImage.Question);

            if (res != MessageBoxResult.Yes) return;

            _isFlashing = true;
            SetControlsEnabled(false);
            _progBar.Value = 5;
            _progSubtext.Text = "5%";
            _progText.Text = "Запуск сервисной прошивки...";
            UpdateStageCheckpoint(0);

            Log("================================================================================");
            Log(">>> СТАРТ: " + modeName + " (Ревизия: " + rev + ")");

            ThreadPool.QueueUserWorkItem(_ =>
            {
                try
                {
                    string ps1 = GetScriptPath("flash_service_port.ps1");
                    string args = "-NoProfile -ExecutionPolicy Bypass -File \"" + ps1 + "\" -Revision \"" + rev + "\" -NoPause";
                    if (wipeData) args += " -WipeData";
                    else args += " -KeepData";

                    RunProcess("powershell.exe", args, (pct, msg) =>
                    {
                        Dispatcher.Invoke(() =>
                        {
                            if (pct >= 0)
                            {
                                _progBar.Value = pct;
                                _progSubtext.Text = pct + "%";
                            }
                            if (!string.IsNullOrEmpty(msg)) _progText.Text = msg;

                            if (pct < 25) UpdateStageCheckpoint(0);
                            else if (pct < 45) UpdateStageCheckpoint(1);
                            else if (pct < 90) UpdateStageCheckpoint(2);
                            else UpdateStageCheckpoint(3);
                        });
                    });

                    Dispatcher.Invoke(() =>
                    {
                        _progBar.Value = 100;
                        _progSubtext.Text = "100%";
                        _progText.Text = "Прошивка успешно завершена!";
                        UpdateStageCheckpoint(4);
                        Log("================================================================================");
                        Log(">>> УСПЕХ: OpenStation Max успешно прошита в системные разделы!");

                        GoToStep(4);
                    });
                }
                catch (Exception ex)
                {
                    Dispatcher.Invoke(() =>
                    {
                        _progText.Text = "Ошибка сервисной прошивки!";
                        Log("[ОШИБКА ПРОШИВКИ] " + ex.Message);
                        MessageBox.Show("Ошибка прошивки: " + ex.Message, "Ошибка", MessageBoxButton.OK, MessageBoxImage.Error);
                    });
                }
                finally
                {
                    Dispatcher.Invoke(() =>
                    {
                        _isFlashing = false;
                        SetControlsEnabled(true);
                        CheckDeviceStatus();
                    });
                }
            });
        }

        private void RunAfterUbt()
        {
            if (_isFlashing) return;

            string rev = _selectedRevision;
            var res = MessageBox.Show(
                "Запустить снятие бутлупа (After UBT)?\n\n" +
                "Ревизия: " + rev + "\n\n" +
                "Этот режим выполняет:\n" +
                "• Запись правильного раздела misc (uboot-command: отключение AVB2 и permissive bootargs);\n" +
                "• Снятие lock=10000000 и avb2=0 в U-Boot;\n" +
                "• Устраняет зависание на логотипе Яндекса после прошивки через USB Burning Tool.",
                "Снятие бутлупа",
                MessageBoxButton.YesNo,
                MessageBoxImage.Question);

            if (res != MessageBoxResult.Yes) return;

            _isFlashing = true;
            SetControlsEnabled(false);
            _progBar.Value = 20;
            _progSubtext.Text = "20%";
            _progText.Text = "Выполнение After UBT (снятие AVB2 / lock=10000000)...";
            UpdateStageCheckpoint(1);

            Log("================================================================================");
            Log(">>> СТАРТ: After UBT — Снятие бутлупа и блокировок (Ревизия: " + rev + ")");

            ThreadPool.QueueUserWorkItem(_ =>
            {
                try
                {
                    string ps1 = GetScriptPath("flash_service_port.ps1");
                    string args = "-NoProfile -ExecutionPolicy Bypass -File \"" + ps1 + "\" -Revision \"" + rev + "\" -AfterUbt -NoPause";

                    RunProcess("powershell.exe", args, (pct, msg) =>
                    {
                        Dispatcher.Invoke(() =>
                        {
                            if (pct >= 0)
                            {
                                _progBar.Value = pct;
                                _progSubtext.Text = pct + "%";
                            }
                            if (!string.IsNullOrEmpty(msg)) _progText.Text = msg;
                        });
                    });

                    Dispatcher.Invoke(() =>
                    {
                        _progBar.Value = 100;
                        _progSubtext.Text = "100%";
                        _progText.Text = "After UBT успешно применён! Бутлуп устранён.";
                        UpdateStageCheckpoint(4);
                        Log(">>> УСПЕХ: Переменные U-Boot обновлены. AVB2 разблокирован, misc прошит.");

                        GoToStep(4);
                    });
                }
                catch (Exception ex)
                {
                    Dispatcher.Invoke(() =>
                    {
                        _progText.Text = "Ошибка After UBT!";
                        Log("[ОШИБКА After UBT] " + ex.Message);
                        MessageBox.Show("Ошибка After UBT: " + ex.Message, "Ошибка", MessageBoxButton.OK, MessageBoxImage.Error);
                    });
                }
                finally
                {
                    Dispatcher.Invoke(() =>
                    {
                        _isFlashing = false;
                        SetControlsEnabled(true);
                        CheckDeviceStatus();
                    });
                }
            });
        }

        private void RunAdbFlash()
        {
            if (_isFlashing) return;

            var res = MessageBox.Show(
                "Запустить быструю установку OpenStation Max по ADB?\n\n" +
                "Этот способ подходит для работающих станций:\n" +
                "• Устанавливает системный демон openstationd v1.3.5;\n" +
                "• Устанавливает ТВ-лаунчер OpenStationLauncher и клавиатуру OpenStationIME;\n" +
                "• Настраивает плеер Яндекс Музыки (Моя волна) и голосовой ассистент;\n" +
                "• Не требует разборки колонки и перемычек.",
                "Прошивка по ADB",
                MessageBoxButton.YesNo,
                MessageBoxImage.Question);

            if (res != MessageBoxResult.Yes) return;

            _isFlashing = true;
            SetControlsEnabled(false);
            _progBar.Value = 10;
            _progSubtext.Text = "10%";
            _progText.Text = "Подключение к станции по ADB и установка компонентов...";
            Log("================================================================================");
            Log(">>> СТАРТ: Быстрая прошивка OpenStation Max по ADB...");

            ThreadPool.QueueUserWorkItem(_ =>
            {
                try
                {
                    string ps1 = GetScriptPath("deploy.ps1");
                    string args = "-NoProfile -ExecutionPolicy Bypass -File \"" + ps1 + "\" -UseFirmware -Interactive";

                    RunProcess("powershell.exe", args, (pct, msg) =>
                    {
                        Dispatcher.Invoke(() =>
                        {
                            if (pct >= 0)
                            {
                                _progBar.Value = pct;
                                _progSubtext.Text = pct + "%";
                            }
                            if (!string.IsNullOrEmpty(msg)) _progText.Text = msg;
                        });
                    });

                    Dispatcher.Invoke(() =>
                    {
                        _progBar.Value = 100;
                        _progSubtext.Text = "100%";
                        _progText.Text = "Установка по ADB успешно завершена!";
                        Log(">>> УСПЕХ: OpenStation Max установлена на Станцию по ADB!");

                        GoToStep(4);
                    });
                }
                catch (Exception ex)
                {
                    Dispatcher.Invoke(() =>
                    {
                        _progText.Text = "Ошибка прошивки по ADB!";
                        Log("[ОШИБКА ADB] " + ex.Message);
                        MessageBox.Show("Ошибка установки по ADB: " + ex.Message, "Ошибка", MessageBoxButton.OK, MessageBoxImage.Error);
                    });
                }
                finally
                {
                    Dispatcher.Invoke(() =>
                    {
                        _isFlashing = false;
                        SetControlsEnabled(true);
                        CheckDeviceStatus();
                    });
                }
            });
        }

        private void RunUpdateEntry()
        {
            if (_isFlashing) return;

            string py = FindPythonExe();
            if (string.IsNullOrEmpty(py))
            {
                var ask = MessageBox.Show(
                    "Python 3 не найден на вашем компьютере!\n\n" +
                    "Скачать портативный Python 3 автоматически в папку tools\\python-embed\\?",
                    "Python 3",
                    MessageBoxButton.YesNo,
                    MessageBoxImage.Question);
                if (ask == MessageBoxResult.Yes)
                {
                    DownloadPortablePython();
                    return;
                }
                return;
            }

            string rev = _selectedRevision;
            _isFlashing = true;
            SetControlsEnabled(false);
            _progBar.Value = 25;
            _progSubtext.Text = "25%";
            _progText.Text = "Вход в режим Update (boot-g12, ревизия: " + rev + ")...";
            Log("================================================================================");
            Log(">>> ВХОД ДЛЯ АПДЕЙТА: Запуск boot-g12.py (Python: " + py + ", Ревизия: " + rev + ")");

            ThreadPool.QueueUserWorkItem(_ =>
            {
                try
                {
                    string script = Path.Combine(_serviceFlashDir, "boot-g12.py");
                    string args = "\"" + script + "\" --revision " + rev;

                    RunProcess(py, args, (pct, msg) =>
                    {
                        Dispatcher.Invoke(() =>
                        {
                            if (pct >= 0)
                            {
                                _progBar.Value = pct;
                                _progSubtext.Text = pct + "%";
                            }
                            if (!string.IsNullOrEmpty(msg)) _progText.Text = msg;
                        });
                    });

                    Dispatcher.Invoke(() =>
                    {
                        _progBar.Value = 100;
                        _progSubtext.Text = "100%";
                        _progText.Text = "WorldCup включен! Станция в режиме U-Boot Update.";
                        Log(">>> УСПЕХ: Станция переведена в режим U-Boot Update. Теперь можно прошивать!");
                        MessageBox.Show(
                            "WorldCup включен!\n\n" +
                            "Станция переведена в режим U-Boot Update.\n" +
                            "Теперь можно переходить к Шагу 3.",
                            "Режим Update активен",
                            MessageBoxButton.OK,
                            MessageBoxImage.Information);
                    });
                }
                catch (Exception ex)
                {
                    Dispatcher.Invoke(() =>
                    {
                        _progText.Text = "Ошибка входа в режим Update!";
                        Log("[ОШИБКА ВХОДА] " + ex.Message);
                        MessageBox.Show("Ошибка перехода в режим Update: " + ex.Message, "Ошибка", MessageBoxButton.OK, MessageBoxImage.Error);
                    });
                }
                finally
                {
                    Dispatcher.Invoke(() =>
                    {
                        _isFlashing = false;
                        SetControlsEnabled(true);
                        CheckDeviceStatus();
                    });
                }
            });
        }

        private void DownloadPortablePython()
        {
            _progText.Text = "Скачивание портативного Python 3.12 Embed...";
            _progBar.Value = 20;
            _progSubtext.Text = "20%";
            Log(">>> Скачивание портативного Python 3.12 Embed в tools\\python-embed...");

            ThreadPool.QueueUserWorkItem(_ =>
            {
                try
                {
                    string toolsDir = Path.Combine(_baseDir, "tools");
                    string destDir = Path.Combine(toolsDir, "python-embed");
                    string zipTmp = Path.Combine(toolsDir, "python-embed.zip");
                    Directory.CreateDirectory(destDir);

                    string url = Environment.Is64BitOperatingSystem
                        ? "https://www.python.org/ftp/python/3.12.8/python-3.12.8-embed-amd64.zip"
                        : "https://www.python.org/ftp/python/3.12.8/python-3.12.8-embed-win32.zip";

                    using (var wc = new WebClient())
                    {
                        wc.DownloadFile(url, zipTmp);
                    }

                    System.IO.Compression.ZipFile.ExtractToDirectory(zipTmp, destDir);
                    File.Delete(zipTmp);

                    Dispatcher.Invoke(() =>
                    {
                        _progBar.Value = 100;
                        _progSubtext.Text = "100%";
                        _progText.Text = "Портативный Python 3 готов!";
                        Log("[✓] Портативный Python 3 успешно загружен в tools\\python-embed.");
                        CheckPrerequisites();
                    });
                }
                catch (Exception ex)
                {
                    Dispatcher.Invoke(() =>
                    {
                        Log("[!] Ошибка скачивания Python: " + ex.Message);
                        MessageBox.Show("Не удалось автоматически скачать Python: " + ex.Message, "Ошибка", MessageBoxButton.OK, MessageBoxImage.Warning);
                    });
                }
            });
        }

        private void RunInstallDriver()
        {
            if (_isFlashing) return;

            _progText.Text = "Установка USB-драйвера WorldCup...";
            _progBar.Value = 30;
            _progSubtext.Text = "30%";
            Log(">>> Запуск автоматической установки драйвера WorldCup...");

            ThreadPool.QueueUserWorkItem(_ =>
            {
                try
                {
                    string script = GetScriptPath("install_driver.ps1");
                    string args = "-NoProfile -ExecutionPolicy Bypass -File \"" + script + "\" -NoPause";

                    var psi = new ProcessStartInfo
                    {
                        FileName = "powershell.exe",
                        Arguments = args,
                        Verb = "RunAs",
                        UseShellExecute = true
                    };

                    var p = Process.Start(psi);
                    p.WaitForExit();

                    Dispatcher.Invoke(() =>
                    {
                        _progBar.Value = 100;
                        _progSubtext.Text = "100%";
                        _progText.Text = "Драйвер WorldCup успешно установлен!";
                        Log("[+] Драйвер установлен в систему. Проверяем статус устройства...");
                        CheckPrerequisites();
                        CheckDeviceStatus();
                    });
                }
                catch (Exception ex)
                {
                    Dispatcher.Invoke(() =>
                    {
                        _progText.Text = "Не удалось запустить установку драйвера.";
                        Log("[ОШИБКА Драйвера] " + ex.Message);
                    });
                }
            });
        }

        private void RebootToBootloader()
        {
            if (_isFlashing) return;
            Log(">>> Отправка команды перезагрузки в режим прошивки (adb reboot update)...");
            ThreadPool.QueueUserWorkItem(_ =>
            {
                try
                {
                    string adb = FindAdbExe();
                    var psi = new ProcessStartInfo(adb, "reboot update")
                    {
                        UseShellExecute = false,
                        CreateNoWindow = true,
                        RedirectStandardOutput = true,
                        RedirectStandardError = true
                    };
                    using (var p = Process.Start(psi))
                    {
                        p.WaitForExit(3000);
                    }
                    Dispatcher.Invoke(() => Log("[✓] Команда adb reboot update отправлена. Станция переходит в WorldCup!"));
                }
                catch (Exception ex)
                {
                    Dispatcher.Invoke(() => Log("[!] Ошибка ADB reboot: " + ex.Message));
                }
            });
        }

        private void CaptureTvScreenshot()
        {
            if (_isFlashing) return;
            Log(">>> Захват скриншота экрана ТВ через ADB...");
            ThreadPool.QueueUserWorkItem(_ =>
            {
                try
                {
                    string adb = FindAdbExe();
                    string remotePath = "/data/local/tmp/screen.png";
                    string saveDir = Path.Combine(_baseDir, "screenshots");
                    Directory.CreateDirectory(saveDir);
                    string savePath = Path.Combine(saveDir, "tv_" + DateTime.Now.ToString("yyyyMMdd_HHmmss") + ".png");

                    var psiCap = new ProcessStartInfo(adb, "shell screencap -p " + remotePath) { UseShellExecute = false, CreateNoWindow = true };
                    using (var p = Process.Start(psiCap)) p.WaitForExit(3000);

                    var psiPull = new ProcessStartInfo(adb, "pull " + remotePath + " \"" + savePath + "\"") { UseShellExecute = false, CreateNoWindow = true };
                    using (var p = Process.Start(psiPull)) p.WaitForExit(3000);

                    Dispatcher.Invoke(() =>
                    {
                        if (File.Exists(savePath))
                        {
                            Log("[+] Скриншот экрана ТВ сохранён: " + savePath);
                            Process.Start(new ProcessStartInfo { FileName = savePath, UseShellExecute = true });
                        }
                        else
                        {
                            Log("[!] Не удалось загрузить скриншот ТВ.");
                        }
                    });
                }
                catch (Exception ex)
                {
                    Dispatcher.Invoke(() => Log("[!] Ошибка снятия скриншота: " + ex.Message));
                }
            });
        }

        private void OpenWebUi()
        {
            ThreadPool.QueueUserWorkItem(_ =>
            {
                string targetUrl = "http://192.168.0.20:8080";
                try
                {
                    if (_isAdbConnected)
                    {
                        string adb = FindAdbExe();
                        var psi = new ProcessStartInfo(adb, "shell ip -f inet addr show wlan0")
                        {
                            UseShellExecute = false,
                            CreateNoWindow = true,
                            RedirectStandardOutput = true
                        };
                        using (var p = Process.Start(psi))
                        {
                            string outStr = p.StandardOutput.ReadToEnd();
                            p.WaitForExit(1000);
                            var m = System.Text.RegularExpressions.Regex.Match(outStr, @"inet\s+(\d+\.\d+\.\d+\.\d+)");
                            if (m.Success)
                            {
                                targetUrl = "http://" + m.Groups[1].Value + ":8080";
                            }
                        }
                    }
                }
                catch { }

                try
                {
                    Process.Start(new ProcessStartInfo { FileName = targetUrl, UseShellExecute = true });
                }
                catch
                {
                    try
                    {
                        Process.Start(new ProcessStartInfo { FileName = "http://station-max.local:8080", UseShellExecute = true });
                    }
                    catch (Exception ex)
                    {
                        Dispatcher.Invoke(() =>
                        {
                            MessageBox.Show("Не удалось открыть браузер: " + ex.Message, "Ошибка", MessageBoxButton.OK, MessageBoxImage.Information);
                        });
                    }
                }
            });
        }

        private string FindAdbExe()
        {
            string[] candidates = new string[]
            {
                Path.Combine(_serviceFlashDir, "adb", "adb.exe"),
                Path.Combine(_baseDir, "adb", "adb.exe"),
                Path.Combine(_baseDir, "platform-tools", "adb.exe"),
                Path.Combine(_baseDir, "tools", "platform-tools", "adb.exe")
            };
            foreach (var path in candidates)
            {
                if (File.Exists(path)) return path;
            }
            return "adb.exe";
        }

        private string FindPythonExe()
        {
            string embedPy = Path.Combine(_baseDir, "tools", "python-embed", "python.exe");
            if (File.Exists(embedPy)) return embedPy;

            string localApp = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            var explicitPaths = new List<string>
            {
                @"C:\Program Files\Python39\python.exe",
                @"C:\Program Files\Python310\python.exe",
                @"C:\Program Files\Python311\python.exe",
                @"C:\Program Files\Python312\python.exe",
                @"C:\Python39\python.exe",
                @"C:\Python310\python.exe",
                @"C:\Python311\python.exe",
                @"C:\Python312\python.exe"
            };

            foreach (var p in explicitPaths)
            {
                if (File.Exists(p)) return p;
            }

            try
            {
                if (!string.IsNullOrEmpty(localApp))
                {
                    string pyDir = Path.Combine(localApp, "Programs", "Python");
                    if (Directory.Exists(pyDir))
                    {
                        var pyExes = Directory.GetFiles(pyDir, "python.exe", SearchOption.AllDirectories);
                        if (pyExes.Length > 0) return pyExes[0];
                    }
                }
            }
            catch { }

            string[] testCommands = new string[] { "python.exe", "py.exe", "python3.exe" };
            foreach (var cmd in testCommands)
            {
                try
                {
                    var psi = new ProcessStartInfo(cmd, "--version")
                    {
                        UseShellExecute = false,
                        CreateNoWindow = true,
                        RedirectStandardOutput = true,
                        RedirectStandardError = true
                    };
                    using (var p = Process.Start(psi))
                    {
                        p.WaitForExit(1000);
                        if (p.ExitCode == 0) return cmd;
                    }
                }
                catch { }
            }

            return null;
        }

        private void RunProcess(string fileName, string args, Action<int, string> onProgress)
        {
            var psi = new ProcessStartInfo
            {
                FileName = fileName,
                Arguments = args,
                WorkingDirectory = _baseDir,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8
            };

            var errBuilder = new StringBuilder();

            using (var proc = new Process { StartInfo = psi })
            {
                proc.OutputDataReceived += (s, e) =>
                {
                    if (e.Data != null)
                    {
                        string line = e.Data;
                        Log(line);

                        // Parse realistic progress
                        if (line.Contains("[1/4]") || line.Contains("[1/6]")) onProgress(15, "Шаг 1: Проверка окружения и разделов...");
                        else if (line.Contains("[2/4]") || line.Contains("boot-g12")) onProgress(30, "Шаг 2: Загрузка U-Boot (BootROM boot-g12)...");
                        else if (line.Contains("vbmeta")) onProgress(45, "Шаг 3: Запись vbmeta (2 МБ)...");
                        else if (line.Contains("vendor")) onProgress(60, "Шаг 3: Запись vendor (512 МБ)...");
                        else if (line.Contains("system")) onProgress(78, "Шаг 3: Запись system (~1.44 ГБ, подождите)...");
                        else if (line.Contains("misc")) onProgress(88, "Шаг 3: Запись misc (защита от бутлупа)...");
                        else if (line.Contains("[4/4]") || line.Contains("Финализация") || line.Contains("After UBT")) onProgress(94, "Шаг 4: Финализация U-Boot и снятие AVB2...");
                    }
                };

                proc.ErrorDataReceived += (s, e) =>
                {
                    if (e.Data != null)
                    {
                        Log("[ERR] " + e.Data);
                        errBuilder.AppendLine(e.Data);
                    }
                };

                proc.Start();
                proc.BeginOutputReadLine();
                proc.BeginErrorReadLine();
                proc.WaitForExit();

                if (proc.ExitCode != 0)
                {
                    string errStr = errBuilder.ToString().Trim();
                    if (proc.ExitCode == -196608 || errStr.Contains("does not exist") || errStr.Contains("не существует"))
                    {
                        throw new Exception(
                            "Файл скрипта не найден (ошибка -196608).\n\n" +
                            "Каталог запуска: " + _baseDir + "\n\n" +
                            "Пожалуйста, убедитесь, что архив OpenStationMax_Flasher распакован полностью в отдельную папку, и запускайте OpenStationFlasher.exe из неё.");
                    }

                    string failMsg = "Процесс завершился с кодом ошибки " + proc.ExitCode;
                    if (!string.IsNullOrEmpty(errStr))
                    {
                        failMsg += "\n\nДетали ошибки:\n" + errStr;
                    }
                    throw new Exception(failMsg);
                }
            }
        }

        private void Log(string message)
        {
            if (string.IsNullOrEmpty(message)) return;
            Dispatcher.Invoke(() =>
            {
                string ts = DateTime.Now.ToString("HH:mm:ss");
                if (_logBox != null)
                {
                    _logBox.AppendText("[" + ts + "] " + message + Environment.NewLine);
                    _logBox.ScrollToEnd();
                }
            });
        }
    }
}
