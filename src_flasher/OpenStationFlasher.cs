using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Management;
using System.Reflection;
using System.Security.Cryptography.X509Certificates;
using System.Security.Principal;
using System.Text;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;

namespace OpenStationFlasher
{
    public class App : Application
    {
        [STAThread]
        public static void Main(string[] args)
        {
            var app = new App();
            var window = new MainWindow();
            app.Run(window);
        }
    }

    public class MainWindow : Window
    {
        private DispatcherTimer _pollTimer;
        private bool _isFlashing = false;
        private bool _isDeviceConnected = false;
        private string _baseDir;
        private string _serviceFlashDir;
        private string _romDir;

        // UI controls
        private Border _statusBadge;
        private TextBlock _statusText;
        private TextBlock _statusSubtext;
        private ComboBox _comboRev;
        private Button _btnFlashWipe;
        private Button _btnFlashKeep;
        private Button _btnUpdateEntry;
        private Button _btnAfterUbt;
        private Button _btnInstallDriver;
        private Button _btnTogglePinout;
        private Border _pinoutCard;
        private ProgressBar _progBar;
        private TextBlock _progText;
        private TextBox _logBox;
        private Button _btnCopyLog;
        private Button _btnOpenWebUi;

        // Prerequisites blocks
        private TextBlock _valDriver;
        private TextBlock _valUboot;
        private TextBlock _valRom;

        public MainWindow()
        {
            _baseDir = AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\');
            _serviceFlashDir = Path.Combine(_baseDir, "service_flash");
            _romDir = Path.Combine(_baseDir, "rom");

            InitWindow();
            InitUI();
            StartDevicePolling();
            Log("OpenStation Max Flasher v1.3.1 запущен.");
            Log("Каталог программы: " + _baseDir);
            Log("Поддержка ревизий: YNDX-0008, YNDX-00052, YNDX-00053 (Zigbee).");
            CheckPrerequisites();
        }

        private void InitWindow()
        {
            Title = "OpenStation Max — Сервисный прошивальщик v1.3.1";
            Width = 940;
            Height = 840;
            MinWidth = 820;
            MinHeight = 680;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            Background = new SolidColorBrush(Color.FromRgb(8, 10, 16)); // Apple deep glass
            Foreground = new SolidColorBrush(Color.FromRgb(240, 243, 248));
            FontFamily = new FontFamily("Segoe UI Variable, Segoe UI, sans-serif");
        }

        private static bool IsAdministrator()
        {
            try
            {
                var identity = WindowsIdentity.GetCurrent();
                var principal = new WindowsPrincipal(identity);
                return principal.IsInRole(WindowsBuiltInRole.Administrator);
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

        private void OpenWebUi()
        {
            try
            {
                Process.Start(new ProcessStartInfo
                {
                    FileName = "http://192.168.0.20:8080",
                    UseShellExecute = true
                });
            }
            catch
            {
                try
                {
                    Process.Start(new ProcessStartInfo
                    {
                        FileName = "http://station-max.local:8080",
                        UseShellExecute = true
                    });
                }
                catch (Exception ex)
                {
                    MessageBox.Show("Не удалось открыть браузер: " + ex.Message, "Ошибка", MessageBoxButton.OK, MessageBoxImage.Information);
                }
            }
        }

        private void InitUI()
        {
            var rootGrid = new Grid();
            rootGrid.Margin = new Thickness(20);
            rootGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // 0: Header
            rootGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // 1: Prerequisites Bar
            rootGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // 2: Device Status
            rootGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // 3: Pinout (collapsible)
            rootGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // 4: Revision & Hardware Bar
            rootGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // 5: Action Cards
            rootGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // 6: Progress
            rootGrid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) }); // 7: Logs

            // 0. Header
            var headerPanel = new Grid { Margin = new Thickness(0, 0, 0, 12) };
            headerPanel.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            headerPanel.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var titleLeft = new StackPanel();
            var titleRow = new StackPanel { Orientation = Orientation.Horizontal };

            var logoBlock = new TextBlock
            {
                Text = "⚡",
                FontSize = 26,
                Margin = new Thickness(0, 0, 10, 0),
                Foreground = new SolidColorBrush(Color.FromRgb(0, 212, 255))
            };
            titleRow.Children.Add(logoBlock);

            var titleText = new TextBlock
            {
                Text = "OPENSTATION MAX",
                FontSize = 23,
                FontWeight = FontWeights.Bold,
                Foreground = new SolidColorBrush(Color.FromRgb(255, 255, 255))
            };
            titleRow.Children.Add(titleText);

            var verBadge = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(40, 0, 212, 255)),
                BorderBrush = new SolidColorBrush(Color.FromRgb(0, 212, 255)),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(999),
                Margin = new Thickness(12, 3, 0, 3),
                Padding = new Thickness(12, 3, 12, 3)
            };
            verBadge.Child = new TextBlock
            {
                Text = "v1.3.1",
                FontSize = 12,
                FontWeight = FontWeights.SemiBold,
                Foreground = new SolidColorBrush(Color.FromRgb(0, 212, 255))
            };
            titleRow.Children.Add(verBadge);

            bool isAdmin = IsAdministrator();
            if (isAdmin)
            {
                var adminBadge = new Border
                {
                    Background = new SolidColorBrush(Color.FromArgb(40, 46, 160, 67)),
                    BorderBrush = new SolidColorBrush(Color.FromRgb(46, 160, 67)),
                    BorderThickness = new Thickness(1),
                    CornerRadius = new CornerRadius(999),
                    Margin = new Thickness(10, 3, 0, 3),
                    Padding = new Thickness(10, 3, 10, 3)
                };
                adminBadge.Child = new TextBlock
                {
                    Text = "🛡 Администратор",
                    FontSize = 11,
                    FontWeight = FontWeights.SemiBold,
                    Foreground = new SolidColorBrush(Color.FromRgb(63, 185, 80))
                };
                titleRow.Children.Add(adminBadge);
            }
            else
            {
                var btnAdmin = CreateButton("🛡 Запуск от Администратора", Color.FromRgb(80, 50, 15), Color.FromRgb(255, 210, 100));
                btnAdmin.Margin = new Thickness(10, 0, 0, 0);
                btnAdmin.Padding = new Thickness(10, 4, 10, 4);
                btnAdmin.FontSize = 11;
                btnAdmin.ToolTip = "Перезапустить прошивальщик с правами Администратора для гарантированной установки драйверов";
                btnAdmin.Click += (s, e) => RestartAsAdmin();
                titleRow.Children.Add(btnAdmin);
            }

            titleLeft.Children.Add(titleRow);

            var subTitle = new TextBlock
            {
                Text = "Сервисная прошивка, раскирпичивание, переход в Update и After UBT для ревизий YNDX-0008, 00052 и 00053 (Zigbee).",
                FontSize = 12,
                Foreground = new SolidColorBrush(Color.FromRgb(139, 148, 158)),
                Margin = new Thickness(0, 4, 0, 0)
            };
            titleLeft.Children.Add(subTitle);
            Grid.SetColumn(titleLeft, 0);
            headerPanel.Children.Add(titleLeft);

            _btnOpenWebUi = CreateButton("🌐 Открыть Веб-пульт", Color.FromRgb(20, 28, 42), Color.FromRgb(88, 166, 255));
            _btnOpenWebUi.Padding = new Thickness(12, 6, 12, 6);
            _btnOpenWebUi.FontSize = 12;
            _btnOpenWebUi.ToolTip = "Открыть веб-интерфейс Станции Макс в браузере (http://192.168.0.20:8080)";
            _btnOpenWebUi.Click += (s, e) => OpenWebUi();
            Grid.SetColumn(_btnOpenWebUi, 1);
            headerPanel.Children.Add(_btnOpenWebUi);

            Grid.SetRow(headerPanel, 0);
            rootGrid.Children.Add(headerPanel);

            // 1. Prerequisites Bar
            var prereqGrid = new Grid { Margin = new Thickness(0, 0, 0, 10) };
            prereqGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            prereqGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            prereqGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            var p1 = CreatePrereqBadge("🔧 Драйвер WorldCup", out _valDriver);
            Grid.SetColumn(p1, 0);
            prereqGrid.Children.Add(p1);

            var p2 = CreatePrereqBadge("💾 Загрузчик U-Boot", out _valUboot);
            Grid.SetColumn(p2, 1);
            prereqGrid.Children.Add(p2);

            var p3 = CreatePrereqBadge("📦 Образы ROM", out _valRom);
            Grid.SetColumn(p3, 2);
            prereqGrid.Children.Add(p3);

            Grid.SetRow(prereqGrid, 1);
            rootGrid.Children.Add(prereqGrid);

            // 2. Device Status Card
            _statusBadge = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(200, 18, 24, 38)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(50, 255, 255, 255)),
                BorderThickness = new Thickness(1.2),
                CornerRadius = new CornerRadius(22),
                Padding = new Thickness(18, 14, 18, 14),
                Margin = new Thickness(0, 0, 0, 10)
            };

            var statusGrid = new Grid();
            statusGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            statusGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var statusTextStack = new StackPanel();
            _statusText = new TextBlock
            {
                Text = "○ Ожидание подключения Станции Макс...",
                FontSize = 15,
                FontWeight = FontWeights.SemiBold,
                Foreground = new SolidColorBrush(Color.FromRgb(210, 153, 34))
            };
            statusTextStack.Children.Add(_statusText);

            _statusSubtext = new TextBlock
            {
                Text = "Замкните пины 6 и 3 перемычкой, подключите сервисный Type-C к ПК и включите 19V питание.",
                FontSize = 12,
                Foreground = new SolidColorBrush(Color.FromRgb(139, 148, 158)),
                Margin = new Thickness(0, 4, 0, 0)
            };
            statusTextStack.Children.Add(_statusSubtext);
            Grid.SetColumn(statusTextStack, 0);
            statusGrid.Children.Add(statusTextStack);

            _btnTogglePinout = CreateButton("Распиновка порта", Color.FromRgb(33, 38, 45), Color.FromRgb(201, 209, 217));
            _btnTogglePinout.Padding = new Thickness(12, 6, 12, 6);
            _btnTogglePinout.Click += (s, e) =>
            {
                _pinoutCard.Visibility = (_pinoutCard.Visibility == Visibility.Visible) ? Visibility.Collapsed : Visibility.Visible;
            };
            Grid.SetColumn(_btnTogglePinout, 1);
            statusGrid.Children.Add(_btnTogglePinout);

            _statusBadge.Child = statusGrid;
            Grid.SetRow(_statusBadge, 2);
            rootGrid.Children.Add(_statusBadge);

            // 3. Pinout Helper (Initially Collapsed)
            _pinoutCard = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(235, 12, 17, 28)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(90, 0, 212, 255)),
                BorderThickness = new Thickness(1.2),
                CornerRadius = new CornerRadius(22),
                Padding = new Thickness(16),
                Margin = new Thickness(0, 0, 0, 10),
                Visibility = Visibility.Collapsed
            };

            var pinoutStack = new StackPanel();
            var pinTitle = new TextBlock
            {
                Text = "Схема подключения 7-пинового сервисного разъёма (все ревизии: 0008, 00052, 00053):",
                FontWeight = FontWeights.Bold,
                FontSize = 13,
                Foreground = new SolidColorBrush(Color.FromRgb(88, 166, 255)),
                Margin = new Thickness(0, 0, 0, 8)
            };
            pinoutStack.Children.Add(pinTitle);

            var pinDesc = new TextBlock
            {
                Text = "Разъём расположен снизу Станции Макс под резиновой заглушкой возле AUX и радиатора.\n" +
                       "Нумерация 1..7 слева направо (от круглого аудиогнезда AUX 3.5mm):\n\n" +
                       "  [AUX]   [1] DATA+    [2] DATA-    [3] GND      [4] NC     [5] TX     [6] BOOT_EN   [7] VCC\n" +
                       "         (Зелёный)    (Белый)    (Чёрный)               (UART)     (Замкнуть)    (3.3V)\n" +
                       "             |            |          |                                |\n" +
                       "             +--- USB ----+          +---------- Перемычка -----------+\n\n" +
                       "Пошаговая инструкция для первого подключения:\n" +
                       "  1. Замкните пин [6] (BOOT) на пин [3] (GND) скрепкой, пинцетом или проводом.\n" +
                       "  2. Подключите USB-кабель от ПК к пинам: 1 (DATA+), 2 (DATA-), 3 (GND). (+5V НЕ подключать!).\n" +
                       "  3. Вставьте штатный блок питания 19V в Станцию Макс.\n" +
                       "  4. Индикатор статуса выше станет зелёным: «Станция Макс подключена (1B8E:C003)». \n" +
                       "  5. Нажмите «⚡ Чистая прошивка». Станция сама перейдёт в режим Update и запишет систему.\n" +
                       "  6. ПОСЛЕ ПРОШИВКИ: обязательно снимите перемычку с пина 6 и отключите питание 19V на 3 секунды!",
                FontSize = 12,
                LineHeight = 18,
                Foreground = new SolidColorBrush(Color.FromRgb(201, 209, 217)),
                FontFamily = new FontFamily("Consolas, Courier New, monospace")
            };
            pinoutStack.Children.Add(pinDesc);
            _pinoutCard.Child = pinoutStack;
            Grid.SetRow(_pinoutCard, 3);
            rootGrid.Children.Add(_pinoutCard);

            // 4. Revision Selector & Fast Action Bar
            var revBar = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(200, 18, 24, 38)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(45, 255, 255, 255)),
                BorderThickness = new Thickness(1.2),
                CornerRadius = new CornerRadius(22),
                Padding = new Thickness(16, 10, 16, 10),
                Margin = new Thickness(0, 0, 0, 10)
            };

            var revBarGrid = new Grid();
            revBarGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            revBarGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            revBarGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var revLbl = new TextBlock
            {
                Text = "Ревизия Станции:",
                FontSize = 13,
                FontWeight = FontWeights.SemiBold,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(0, 0, 12, 0),
                Foreground = new SolidColorBrush(Color.FromRgb(201, 209, 217))
            };
            Grid.SetColumn(revLbl, 0);
            revBarGrid.Children.Add(revLbl);

            _comboRev = new ComboBox
            {
                Height = 30,
                Width = 260,
                HorizontalAlignment = HorizontalAlignment.Left,
                Background = new SolidColorBrush(Color.FromRgb(33, 38, 45)),
                Foreground = new SolidColorBrush(Color.FromRgb(255, 255, 255)),
                BorderBrush = new SolidColorBrush(Color.FromRgb(48, 54, 61)),
                FontSize = 12
            };
            _comboRev.Items.Add(new ComboBoxItem { Content = "Авто (пробовать все)", Tag = "auto", IsSelected = true });
            _comboRev.Items.Add(new ComboBoxItem { Content = "YNDX-0008 / YNDX-00052", Tag = "0008" });
            _comboRev.Items.Add(new ComboBoxItem { Content = "YNDX-00053 (Zigbee 2023+)", Tag = "00053" });
            Grid.SetColumn(_comboRev, 1);
            revBarGrid.Children.Add(_comboRev);

            _btnInstallDriver = CreateButton("🔧 Установить драйвер WorldCup", Color.FromRgb(35, 134, 54), Color.FromRgb(255, 255, 255));
            _btnInstallDriver.Padding = new Thickness(12, 6, 12, 6);
            _btnInstallDriver.FontWeight = FontWeights.SemiBold;
            _btnInstallDriver.Click += (s, e) => RunInstallDriver();
            Grid.SetColumn(_btnInstallDriver, 2);
            revBarGrid.Children.Add(_btnInstallDriver);

            revBar.Child = revBarGrid;
            Grid.SetRow(revBar, 4);
            rootGrid.Children.Add(revBar);

            // 5. Action Cards (Clean, Distinct, 3 Main + 1 Utility)
            var actionGrid = new Grid { Margin = new Thickness(0, 0, 0, 10) };
            actionGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            actionGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            actionGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            // Button 1: Flash with Wipe
            _btnFlashWipe = CreateBigActionButton(
                "⚡ Чистая прошивка",
                "Прошивает vbmeta, vendor, system и очищает /data. Полный Unbrick с нуля.",
                Color.FromRgb(0, 212, 255), Color.FromRgb(10, 26, 36));
            _btnFlashWipe.Margin = new Thickness(0, 0, 4, 0);
            _btnFlashWipe.Click += (s, e) => StartFlash(true);
            Grid.SetColumn(_btnFlashWipe, 0);
            actionGrid.Children.Add(_btnFlashWipe);

            // Button 2: Flash Keep Data
            _btnFlashKeep = CreateBigActionButton(
                "🔄 Обновление (/data сохранён)",
                "Прошивает систему, сохраняя ключи, аккаунты и настройки станции.",
                Color.FromRgb(163, 113, 247), Color.FromRgb(26, 19, 40));
            _btnFlashKeep.Margin = new Thickness(4, 0, 4, 0);
            _btnFlashKeep.Click += (s, e) => StartFlash(false);
            Grid.SetColumn(_btnFlashKeep, 1);
            actionGrid.Children.Add(_btnFlashKeep);

            // Button 3: After UBT
            _btnAfterUbt = CreateBigActionButton(
                "🛠 After UBT (Снять бутлуп)",
                "Снимает lock=10000000 и avb2=0. Убирает зависание на логотипе Яндекса.",
                Color.FromRgb(210, 153, 34), Color.FromRgb(32, 26, 16));
            _btnAfterUbt.Margin = new Thickness(4, 0, 0, 0);
            _btnAfterUbt.Click += (s, e) => RunAfterUbt();
            Grid.SetColumn(_btnAfterUbt, 2);
            actionGrid.Children.Add(_btnAfterUbt);

            Grid.SetRow(actionGrid, 5);
            rootGrid.Children.Add(actionGrid);

            // 6. Progress Section
            var progStack = new StackPanel { Margin = new Thickness(0, 0, 0, 8) };
            var progHeaderGrid = new Grid { Margin = new Thickness(0, 0, 0, 4) };
            progHeaderGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            progHeaderGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            _progText = new TextBlock
            {
                Text = "Готов к работе. Выберите действие выше.",
                FontSize = 13,
                Foreground = new SolidColorBrush(Color.FromRgb(201, 209, 217))
            };
            Grid.SetColumn(_progText, 0);
            progHeaderGrid.Children.Add(_progText);

            _btnUpdateEntry = CreateButton("🚀 Вход для апдейта (boot-g12)", Color.FromRgb(26, 32, 44), Color.FromRgb(88, 166, 255));
            _btnUpdateEntry.Padding = new Thickness(8, 3, 8, 3);
            _btnUpdateEntry.FontSize = 11;
            _btnUpdateEntry.ToolTip = "Загрузить BL2/U-Boot в оперативную память (BootROM -> WorldCup Update) без запуска прошивки";
            _btnUpdateEntry.Click += (s, e) => RunUpdateEntry();
            Grid.SetColumn(_btnUpdateEntry, 1);
            progHeaderGrid.Children.Add(_btnUpdateEntry);

            progStack.Children.Add(progHeaderGrid);

            _progBar = new ProgressBar
            {
                Height = 8,
                Minimum = 0,
                Maximum = 100,
                Value = 0,
                Foreground = new SolidColorBrush(Color.FromRgb(0, 212, 255)),
                Background = new SolidColorBrush(Color.FromRgb(33, 38, 45)),
                BorderThickness = new Thickness(0)
            };
            progStack.Children.Add(_progBar);
            Grid.SetRow(progStack, 6);
            rootGrid.Children.Add(progStack);

            // 7. Logs Section
            var logPanel = new Grid();
            logPanel.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            logPanel.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

            var logHeader = new Grid { Margin = new Thickness(0, 0, 0, 6) };
            logHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            logHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var logTitle = new TextBlock
            {
                Text = "Журнал операций:",
                FontSize = 12,
                FontWeight = FontWeights.SemiBold,
                Foreground = new SolidColorBrush(Color.FromRgb(139, 148, 158)),
                VerticalAlignment = VerticalAlignment.Center
            };
            Grid.SetColumn(logTitle, 0);
            logHeader.Children.Add(logTitle);

            _btnCopyLog = CreateButton("Скопировать журнал", Color.FromRgb(33, 38, 45), Color.FromRgb(201, 209, 217));
            _btnCopyLog.Padding = new Thickness(8, 3, 8, 3);
            _btnCopyLog.Click += (s, e) =>
            {
                try
                {
                    Clipboard.SetText(_logBox.Text);
                    MessageBox.Show("Лог скопирован в буфер обмена!", "Готово", MessageBoxButton.OK, MessageBoxImage.Information);
                }
                catch { }
            };
            Grid.SetColumn(_btnCopyLog, 1);
            logHeader.Children.Add(_btnCopyLog);
            Grid.SetRow(logHeader, 0);
            logPanel.Children.Add(logHeader);

            _logBox = new TextBox
            {
                IsReadOnly = true,
                TextWrapping = TextWrapping.Wrap,
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                Background = new SolidColorBrush(Color.FromRgb(10, 12, 16)),
                Foreground = new SolidColorBrush(Color.FromRgb(180, 190, 200)),
                BorderBrush = new SolidColorBrush(Color.FromRgb(48, 54, 61)),
                BorderThickness = new Thickness(1),
                FontFamily = new FontFamily("Consolas, Courier New, monospace"),
                FontSize = 11,
                Padding = new Thickness(8)
            };
            Grid.SetRow(_logBox, 1);
            logPanel.Children.Add(_logBox);

            Grid.SetRow(logPanel, 7);
            rootGrid.Children.Add(logPanel);

            Content = rootGrid;
        }

        private Border CreatePrereqBadge(string title, out TextBlock valBlock)
        {
            var border = new Border
            {
                Background = new SolidColorBrush(Color.FromArgb(170, 16, 22, 34)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(40, 255, 255, 255)),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(14),
                Padding = new Thickness(12, 7, 12, 7),
                Margin = new Thickness(0, 0, 6, 0)
            };
            var sp = new StackPanel { Orientation = Orientation.Horizontal };
            var lbl = new TextBlock
            {
                Text = title + ": ",
                FontSize = 11,
                Foreground = new SolidColorBrush(Color.FromRgb(139, 148, 158)),
                VerticalAlignment = VerticalAlignment.Center
            };
            sp.Children.Add(lbl);

            valBlock = new TextBlock
            {
                Text = "Проверка...",
                FontSize = 11,
                FontWeight = FontWeights.SemiBold,
                Foreground = new SolidColorBrush(Color.FromRgb(210, 153, 34)),
                VerticalAlignment = VerticalAlignment.Center
            };
            sp.Children.Add(valBlock);
            border.Child = sp;
            return border;
        }

        private string GetSelectedRevision()
        {
            var item = _comboRev.SelectedItem as ComboBoxItem;
            return (item != null && item.Tag != null) ? item.Tag.ToString() : "auto";
        }

        private static Style CreateAppleGlassButtonStyle(Color bg, Color fg, int cornerRadius, bool isAccent)
        {
            var style = new Style(typeof(Button));
            var template = new ControlTemplate(typeof(Button));
            var borderFactory = new FrameworkElementFactory(typeof(Border));
            borderFactory.Name = "border";
            borderFactory.SetValue(Border.CornerRadiusProperty, new CornerRadius(cornerRadius));
            borderFactory.SetValue(Border.BackgroundProperty, new SolidColorBrush(bg));
            borderFactory.SetValue(Border.BorderBrushProperty, new SolidColorBrush(isAccent ? Color.FromArgb(140, 255, 255, 255) : Color.FromArgb(45, 255, 255, 255)));
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
            hoverTrigger.Setters.Add(new Setter(Border.BorderBrushProperty, new SolidColorBrush(Color.FromArgb(200, 255, 255, 255)), "border"));
            template.Triggers.Add(hoverTrigger);

            style.Setters.Add(new Setter(Button.TemplateProperty, template));
            style.Setters.Add(new Setter(Control.ForegroundProperty, new SolidColorBrush(fg)));
            style.Setters.Add(new Setter(FrameworkElement.CursorProperty, Cursors.Hand));
            return style;
        }

        private Button CreateButton(string text, Color bg, Color fg)
        {
            var btn = new Button
            {
                Content = text,
                Padding = new Thickness(14, 7, 14, 7),
                Cursor = Cursors.Hand,
                Style = CreateAppleGlassButtonStyle(bg, fg, 999, false)
            };
            return btn;
        }

        private Button CreateBigActionButton(string title, string subtitle, Color accentColor, Color bgColor)
        {
            var btn = new Button
            {
                Cursor = Cursors.Hand,
                Padding = new Thickness(16, 14, 16, 14),
                HorizontalContentAlignment = HorizontalAlignment.Left,
                Style = CreateAppleGlassButtonStyle(bgColor, Color.FromRgb(255, 255, 255), 24, true)
            };

            var stack = new StackPanel();
            var tBlock = new TextBlock
            {
                Text = title,
                FontSize = 14,
                FontWeight = FontWeights.Bold,
                Foreground = new SolidColorBrush(accentColor)
            };
            stack.Children.Add(tBlock);

            var sBlock = new TextBlock
            {
                Text = subtitle,
                FontSize = 11,
                Foreground = new SolidColorBrush(Color.FromRgb(150, 160, 175)),
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 5, 0, 0)
            };
            stack.Children.Add(sBlock);

            btn.Content = stack;
            return btn;
        }

        private void StartDevicePolling()
        {
            _pollTimer = new DispatcherTimer();
            _pollTimer.Interval = TimeSpan.FromSeconds(1.5);
            _pollTimer.Tick += (s, e) => CheckDeviceStatus();
            _pollTimer.Start();
            CheckDeviceStatus();
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

                Dispatcher.Invoke(() =>
                {
                    if (connected != _isDeviceConnected)
                    {
                        _isDeviceConnected = connected;
                        if (connected)
                        {
                            if (driverError)
                            {
                                _statusText.Text = "⚠️ Станция подключена, но драйвер НЕ установлен!";
                                _statusText.Foreground = new SolidColorBrush(Color.FromRgb(210, 153, 34));
                                _statusSubtext.Text = errDetail + ". Нажмите кнопку «🔧 Установить драйвер WorldCup» справа!";
                                _statusBadge.BorderBrush = new SolidColorBrush(Color.FromRgb(210, 153, 34));
                                Log("[!] Обнаружено устройство Amlogic без драйвера: " + deviceName + " (" + errDetail + ")");
                            }
                            else
                            {
                                _statusText.Text = "● Станция Макс подключена (" + deviceName + ")";
                                _statusText.Foreground = new SolidColorBrush(Color.FromRgb(46, 160, 67));
                                _statusSubtext.Text = "Устройство готово к загрузке U-Boot или прошивке системных разделов.";
                                _statusBadge.BorderBrush = new SolidColorBrush(Color.FromRgb(46, 160, 67));
                                Log("[+] Обнаружено устройство Amlogic (1B8E:C003): " + deviceName);
                            }
                        }
                        else
                        {
                            _statusText.Text = "○ Ожидание подключения Станции Макс...";
                            _statusText.Foreground = new SolidColorBrush(Color.FromRgb(210, 153, 34));
                            _statusSubtext.Text = "Замкните пины 6 и 3 перемычкой, подключите сервисный Type-C к ПК и включите 19V питание.";
                            _statusBadge.BorderBrush = new SolidColorBrush(Color.FromArgb(50, 255, 255, 255));
                        }
                    }
                });
            });
        }

        private void CheckPrerequisites()
        {
            bool amlOk = false;
            if (Directory.Exists(_serviceFlashDir))
            {
                bool aml8 = File.Exists(Path.Combine(_serviceFlashDir, "aml.img"));
                bool aml53 = File.Exists(Path.Combine(_serviceFlashDir, "aml_00053.img"));
                amlOk = aml8 && aml53;
                if (_valUboot != null)
                {
                    if (amlOk)
                    {
                        _valUboot.Text = "Готов (0008, 00053)";
                        _valUboot.Foreground = new SolidColorBrush(Color.FromRgb(46, 160, 67));
                    }
                    else if (aml8 || aml53)
                    {
                        _valUboot.Text = aml8 ? "0008 готов" : "00053 готов";
                        _valUboot.Foreground = new SolidColorBrush(Color.FromRgb(210, 153, 34));
                    }
                    else
                    {
                        _valUboot.Text = "Отсутствует!";
                        _valUboot.Foreground = new SolidColorBrush(Color.FromRgb(248, 81, 73));
                    }
                }
                Log("[✓] Загрузчики в service_flash: 0008/00052=" + (aml8 ? "OK" : "НЕТ") + ", 00053=" + (aml53 ? "OK" : "НЕТ"));
            }
            else
            {
                if (_valUboot != null)
                {
                    _valUboot.Text = "Каталог не найден";
                    _valUboot.Foreground = new SolidColorBrush(Color.FromRgb(248, 81, 73));
                }
                Log("[!] Каталог service_flash не найден: " + _serviceFlashDir);
            }

            bool romOk = false;
            if (Directory.Exists(_romDir))
            {
                bool sysOk = File.Exists(Path.Combine(_romDir, "system.img"));
                bool venOk = File.Exists(Path.Combine(_romDir, "vendor.img"));
                bool vbOk = File.Exists(Path.Combine(_romDir, "vbmeta.img"));
                romOk = sysOk && venOk && vbOk;
                if (_valRom != null)
                {
                    if (romOk)
                    {
                        _valRom.Text = "Готовы (2.09 ГБ)";
                        _valRom.Foreground = new SolidColorBrush(Color.FromRgb(46, 160, 67));
                    }
                    else
                    {
                        _valRom.Text = "Неполные!";
                        _valRom.Foreground = new SolidColorBrush(Color.FromRgb(248, 81, 73));
                    }
                }
                if (romOk)
                {
                    Log("[✓] Системные образы ROM (vbmeta, vendor, system) обнаружены в каталоге rom/");
                }
                else
                {
                    Log("[!] В каталоге rom/ не хватает файлов: system=" + sysOk + ", vendor=" + venOk + ", vbmeta=" + vbOk);
                }
            }
            else
            {
                if (_valRom != null)
                {
                    _valRom.Text = "Каталог не найден";
                    _valRom.Foreground = new SolidColorBrush(Color.FromRgb(248, 81, 73));
                }
                Log("[!] Каталог rom не найден: " + _romDir);
            }

            // Check Driver files
            bool drvFiles = File.Exists(Path.Combine(_serviceFlashDir, "Drivers", "WorldCup_Device.inf"));
            if (_valDriver != null)
            {
                if (drvFiles)
                {
                    _valDriver.Text = "Файлы готовы (1B8E:C003)";
                    _valDriver.Foreground = new SolidColorBrush(Color.FromRgb(46, 160, 67));
                }
                else
                {
                    _valDriver.Text = "Не найден в Drivers/";
                    _valDriver.Foreground = new SolidColorBrush(Color.FromRgb(248, 81, 73));
                }
            }
        }

        private void SetControlsEnabled(bool enabled)
        {
            _btnFlashWipe.IsEnabled = enabled;
            _btnFlashKeep.IsEnabled = enabled;
            _btnAfterUbt.IsEnabled = enabled;
            _btnUpdateEntry.IsEnabled = enabled;
            _btnInstallDriver.IsEnabled = enabled;
            _comboRev.IsEnabled = enabled;
        }

        private void StartFlash(bool wipeData)
        {
            if (_isFlashing) return;

            string rev = GetSelectedRevision();
            string modeName = wipeData ? "Чистая прошивка (Wipe Data)" : "Прошивка с сохранением /data";
            var res = MessageBox.Show(
                "Начать операцию: " + modeName + "?\n\n" +
                "Ревизия: " + rev + "\n" +
                "Убедитесь, что перемычка 6-3 установлена, кабель Type-C подключен и питание 19V включено.",
                "Подтверждение прошивки",
                MessageBoxButton.YesNo,
                MessageBoxImage.Question);

            if (res != MessageBoxResult.Yes) return;

            _isFlashing = true;
            SetControlsEnabled(false);
            _progBar.Value = 5;
            _progText.Text = "Запуск сервисной прошивки...";
            Log("==================================================");
            Log(">>> СТАРТ: " + modeName + " (Ревизия: " + rev + ")");

            ThreadPool.QueueUserWorkItem(_ =>
            {
                try
                {
                    string ps1 = Path.Combine(_baseDir, "scripts", "flash_service_port.ps1");
                    string args = "-NoProfile -ExecutionPolicy Bypass -File \"" + ps1 + "\" -Revision \"" + rev + "\" -NoPause";
                    if (wipeData) args += " -WipeData";
                    else args += " -KeepData";

                    RunProcess("powershell.exe", args, (pct, msg) =>
                    {
                        Dispatcher.Invoke(() =>
                        {
                            if (pct >= 0) _progBar.Value = pct;
                            if (!string.IsNullOrEmpty(msg)) _progText.Text = msg;
                        });
                    });

                    Dispatcher.Invoke(() =>
                    {
                        _progBar.Value = 100;
                        _progText.Text = "Прошивка успешно завершена!";
                        Log("==================================================");
                        Log(">>> УСПЕХ: Прошивка завершена!");
                        MessageBox.Show(
                            "Прошивка успешно завершена!\n\n" +
                            "ВАЖНО:\n" +
                            "1) Снимите сервисную перемычку с 6 пина!\n" +
                            "2) Перезагрузите Станцию по питанию (отключите 19V на 3 сек).\n" +
                            "3) Колонка запустится в OpenStation Max v1.3.1.\n" +
                            "4) Нажмите «🌐 Открыть Веб-пульт» для быстрой настройки.",
                            "Успех!",
                            MessageBoxButton.OK,
                            MessageBoxImage.Information);
                    });
                }
                catch (Exception ex)
                {
                    Dispatcher.Invoke(() =>
                    {
                        _progText.Text = "Ошибка прошивки!";
                        Log("[ОШИБКА] " + ex.Message);
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

        private void RunUpdateEntry()
        {
            if (_isFlashing) return;

            string rev = GetSelectedRevision();
            _isFlashing = true;
            SetControlsEnabled(false);
            _progBar.Value = 25;
            _progText.Text = "Вход в режим Update (boot-g12, ревизия: " + rev + ")...";
            Log("==================================================");
            Log(">>> ВХОД ДЛЯ АПДЕЙТА: Запуск boot-g12.py (Ревизия: " + rev + ")");

            ThreadPool.QueueUserWorkItem(_ =>
            {
                try
                {
                    string script = Path.Combine(_serviceFlashDir, "boot-g12.py");
                    string args = "\"" + script + "\" --revision " + rev;

                    RunProcess("python.exe", args, (pct, msg) =>
                    {
                        Dispatcher.Invoke(() =>
                        {
                            if (pct >= 0) _progBar.Value = pct;
                            if (!string.IsNullOrEmpty(msg)) _progText.Text = msg;
                        });
                    });

                    Dispatcher.Invoke(() =>
                    {
                        _progBar.Value = 100;
                        _progText.Text = "WorldCup включен! Станция в режиме U-Boot Update.";
                        Log(">>> УСПЕХ: Станция переведена в режим U-Boot Update. Теперь можно прошивать!");
                        MessageBox.Show(
                            "WorldCup включен!\n\n" +
                            "Станция переведена в режим U-Boot Update.\n" +
                            "Теперь можно запускать чистую прошивку, обновление или After UBT.",
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

        private void RunAfterUbt()
        {
            if (_isFlashing) return;

            _isFlashing = true;
            SetControlsEnabled(false);
            _progBar.Value = 20;
            _progText.Text = "Выполнение After UBT (снятие AVB2 / lock=10000000)...";
            Log("==================================================");
            Log(">>> СТАРТ: After UBT (исправление зависания на логотипе Яндекса)");

            ThreadPool.QueueUserWorkItem(_ =>
            {
                try
                {
                    string ps1 = Path.Combine(_baseDir, "scripts", "flash_service_port.ps1");
                    string args = "-NoProfile -ExecutionPolicy Bypass -File \"" + ps1 + "\" -AfterUbt -NoPause";

                    RunProcess("powershell.exe", args, (pct, msg) =>
                    {
                        Dispatcher.Invoke(() =>
                        {
                            if (pct >= 0) _progBar.Value = pct;
                            if (!string.IsNullOrEmpty(msg)) _progText.Text = msg;
                        });
                    });

                    Dispatcher.Invoke(() =>
                    {
                        _progBar.Value = 100;
                        _progText.Text = "After UBT успешно применён!";
                        Log(">>> УСПЕХ: Переменные U-Boot обновлены. AVB2 разблокирован.");
                        MessageBox.Show(
                            "After UBT успешно выполнен!\n\n" +
                            "1) Снимите перемычку с 6 пина.\n" +
                            "2) Перезагрузите Станцию по питанию.\n" +
                            "Бутлуп устранён, колонка успешно загрузится.",
                            "Готово",
                            MessageBoxButton.OK,
                            MessageBoxImage.Information);
                    });
                }
                catch (Exception ex)
                {
                    Dispatcher.Invoke(() =>
                    {
                        _progText.Text = "Ошибка выполнения After UBT!";
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

        private void RunInstallDriver()
        {
            if (_isFlashing) return;

            _progText.Text = "Установка USB-драйвера WorldCup...";
            _progBar.Value = 30;
            Log(">>> Запуск автоматической установки драйвера WorldCup...");

            ThreadPool.QueueUserWorkItem(_ =>
            {
                try
                {
                    string script = Path.Combine(_baseDir, "scripts", "install_driver.ps1");
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

            using (var proc = new Process { StartInfo = psi })
            {
                proc.OutputDataReceived += (s, e) =>
                {
                    if (e.Data != null)
                    {
                        string line = e.Data;
                        Log(line);

                        // Parse progress
                        if (line.Contains("[1/4]")) onProgress(15, "Шаг 1/4: Проверка окружения и драйвера...");
                        else if (line.Contains("[2/4]")) onProgress(35, "Шаг 2/4: Загрузка U-Boot (BootROM boot-g12)...");
                        else if (line.Contains("vbmeta")) onProgress(50, "Шаг 3/4: Запись vbmeta...");
                        else if (line.Contains("vendor")) onProgress(65, "Шаг 3/4: Запись vendor (512 МБ)...");
                        else if (line.Contains("system")) onProgress(80, "Шаг 3/4: Запись system (1.44 ГБ, подождите)...");
                        else if (line.Contains("[4/4]")) onProgress(92, "Шаг 4/4: Финализация U-Boot и After UBT...");
                    }
                };

                proc.ErrorDataReceived += (s, e) =>
                {
                    if (e.Data != null)
                    {
                        Log("[ERR] " + e.Data);
                    }
                };

                proc.Start();
                proc.BeginOutputReadLine();
                proc.BeginErrorReadLine();
                proc.WaitForExit();

                if (proc.ExitCode != 0)
                {
                    throw new Exception("Процесс завершился с кодом ошибки " + proc.ExitCode);
                }
            }
        }

        private void Log(string message)
        {
            if (string.IsNullOrEmpty(message)) return;
            Dispatcher.Invoke(() =>
            {
                string ts = DateTime.Now.ToString("HH:mm:ss");
                _logBox.AppendText("[" + ts + "] " + message + Environment.NewLine);
                _logBox.ScrollToEnd();
            });
        }
    }
}