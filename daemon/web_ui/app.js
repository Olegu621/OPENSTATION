(function () {
  const $ = (id) => document.getElementById(id);
  let state = null;
  let ws = null;
  let wsReconnectTimer = null;
  let saveTimer = null;
  let volTimer = null;
  let ampVolTimer = null;
  let ampVolPendingUntil = 0;
  let toastTimer = null;
  let lastHistSig = "";
  let wizStep = 1;
  let wizPrevStep = 1;
  let wizPopulatedOnce = false;
  let wizAnimRaf = null;
  let wizConfetti = [];
  let otaPendingRestart = false;
  let bootanimBusy = false;
  let wifiAdbBusy = false;

  const COMPONENT_RU = {
    daemon: "Система",
    launcher: "ТВ-экран",
    ime: "Клавиатура",
    bootanimation: "Заставка"
  };

  const ISO_FREQS = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];
  const ISO_LABELS = ["31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"];
  const EQ_PRESETS = {
    balanced: [1.0, 1.5, 1.0, 0.5, 0.0, 0.0, 0.5, 1.0, 1.5, 1.5],
    flat: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
    bass_boost: [4.5, 4.0, 3.0, 1.5, 0.0, -0.5, 0.0, 0.5, 1.0, 1.0],
    vocal: [-2.0, -1.0, 0.0, 1.5, 3.0, 3.5, 2.0, 1.0, 0.5, -0.5],
    night: [-3.0, -2.5, -1.5, 0.0, 1.0, 1.0, 0.5, -0.5, -2.0, -3.0],
    rock: [3.5, 2.5, 1.0, -0.5, -1.0, 0.0, 1.5, 2.5, 3.5, 3.5],
    electronic: [4.0, 3.5, 2.0, 0.5, -0.5, 0.0, 1.0, 2.0, 3.5, 4.0],
    custom: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
  };
  let currentEqBands = [1.0, 1.5, 1.0, 0.5, 0.0, 0.0, 0.5, 1.0, 1.5, 1.5];
  let currentMemoryNotes = [];
  let eqPushTimer = null;

  const INPUT_ACTION_OPTIONS = [
    ["assistant", "Запустить / остановить Алису"],
    ["wyoming", "Home Assistant (Wyoming)"],
    ["play_pause", "Пауза / Воспроизведение"],
    ["next", "Следующий трек"],
    ["prev", "Предыдущий трек"],
    ["mic_mute", "Микрофон вкл / выкл"],
    ["stop_alarm", "Стоп таймера / будильника"],
    ["led_cycle", "Сменить режим подсветки"],
    ["bt_toggle", "Bluetooth колонка вкл / выкл"],
    ["vol_up", "Громче"],
    ["vol_down", "Тише"],
    ["launcher", "Домой (ТВ-экран)"],
    ["none", "Ничего"]
  ];
  document.querySelectorAll(".js-input-act").forEach((sel) => {
    const allowPtt = sel.dataset.allowPtt === "1";
    const list = allowPtt
      ? [
          ["next", "Следующий трек"],
          ["prev", "Предыдущий трек"],
          ["play_pause", "Пауза / Воспроизведение"],
          ["push_to_talk", "Рация — говорить, пока держат"],
          ["stop_alarm", "Стоп таймера / будильника"],
          ["mic_mute", "Микрофон вкл / выкл"],
          ["led_cycle", "Сменить подсветку"],
          ["bt_toggle", "Bluetooth колонка вкл / выкл"],
          ["none", "Ничего"]
        ]
      : INPUT_ACTION_OPTIONS;
    sel.innerHTML = "";
    list.forEach(([val, lbl]) => {
      const opt = document.createElement("option");
      opt.value = val;
      opt.textContent = lbl;
      sel.appendChild(opt);
    });
  });

  function showToast(msg, isErr = false) {
    const t = $("toast");
    if (!t) return;
    t.textContent = msg;
    t.className = "toast show" + (isErr ? " err" : "");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.classList.remove("show"); }, 2600);
  }

  // Build 24 SVG LED circles in a ring
  const svg = $("ringSvg");
  const dots = [];
  if (svg) {
    for (let i = 0; i < 24; i++) {
      const ang = ((i / 24) * Math.PI * 2) - Math.PI / 2;
      const cx = 110 + 88 * Math.cos(ang);
      const cy = 110 + 88 * Math.sin(ang);
      const c = document.createElementNS("http://www.w3.org/2000/svg", "circle");
      c.setAttribute("cx", cx.toFixed(1));
      c.setAttribute("cy", cy.toFixed(1));
      c.setAttribute("r", "7");
      c.setAttribute("fill", "#161b22");
      svg.appendChild(c);
      dots.push(c);
    }
  }

  function switchTab(tabName) {
    if (!$("tab-" + tabName)) tabName = "music";
    document.querySelectorAll(".tab").forEach((b) => {
      b.classList.toggle("active", b.dataset.tab === tabName);
    });
    document.querySelectorAll(".panel").forEach((p) => {
      p.classList.toggle("active", p.id === "tab-" + tabName);
    });
    const src = document.querySelector('.tab[data-tab="' + tabName + '"]');
    if ($("pageTitle") && src) {
      $("pageTitle").textContent = tabName === "alice"
        ? (state?.config?.assistant?.name?.trim() || "Алиса")
        : (src.dataset.title || "");
    }
    [...document.body.classList].filter((c) => c.startsWith("tab-")).forEach((c) => document.body.classList.remove(c));
    document.body.classList.add("tab-" + tabName);
    try { localStorage.setItem("os_tab", tabName); } catch {}
    if (tabName === "home") loadHome();
    if (tabName === "plugins" && window.osPlugins) window.osPlugins.refresh(false);
    if (tabName === "music") {
      refreshMediaReceivers();
      syncMusicProvider();
    }
    setSpotifyFrameVisibility();
  }

  document.querySelectorAll(".tab").forEach((btn) => {
    btn.addEventListener("click", () => switchTab(btn.dataset.tab));
  });

  async function api(path, method = "GET", body = null) {
    const opts = { method, headers: {} };
    if (body !== null) {
      opts.headers["Content-Type"] = "application/json";
      opts.body = JSON.stringify(body);
    }
    const res = await fetch(path, opts);
    let data = null;
    try { data = await res.json(); } catch {}
    if (!res.ok) {
      throw new Error((data && (data.message || data.error)) || `HTTP ${res.status}`);
    }
    return data || {};
  }

  function scheduleConfigPush() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => pushConfig(false), 120);
  }

  async function pushConfig(notify = false) {
    saveTimer = null;
    const payload = {};
    if ($("devHost")) payload.deviceHostname = $("devHost").value.trim() || "station-max";

    if ($("ringColor")) {
      const lr = {
        mode: document.querySelector("#ringModes .seg.active")?.dataset.mode || "rainbow",
        color: $("ringColor").value,
        secondaryColor: $("ringSecColor")?.value || "#7c3aed",
        brightness: parseInt($("ringBrightness")?.value ?? "160", 10),
        autoBrightness: $("ringAutoBrightness") ? $("ringAutoBrightness").checked : true
      };
      if ($("ringMinAuto")) lr.minAutoBrightness = parseInt($("ringMinAuto").value, 10);
      if ($("ringMaxAuto")) lr.maxAutoBrightness = parseInt($("ringMaxAuto").value, 10);
      if ($("ringSpeed")) lr.speed = parseInt($("ringSpeed").value, 10);
      payload.ledRing = lr;
    }

    if ($("scrMode")) {
      const ls = {
        mode: $("scrMode").value,
        text: $("scrText")?.value || "",
        color: $("scrColor")?.value || "#ffffff",
        brightness: parseInt($("scrBri")?.value ?? "100", 10),
        clockStyle: $("clockStyle")?.value || "classic",
        visualizerStyle: $("visualizerStyle")?.value || "pulse_bars",
        autoMusicVisualizer: $("autoMusicVisualizer") ? $("autoMusicVisualizer").checked : true
      };
      if ($("scrAutoBri")) ls.autoBrightness = $("scrAutoBri").checked;
      if ($("scrSpeed")) ls.scrollSpeed = parseInt($("scrSpeed").value, 10);
      payload.ledScreen = ls;
    }

    if ($("eqPreset")) {
      payload.audio = {
        outputRoute: document.querySelector("#audioRoutes .seg.active")?.dataset.route || "auto",
        eqPreset: $("eqPreset").value,
        bassBoost: parseInt($("bassBoost")?.value || "20", 10),
        eqBands: currentEqBands,
        micSensitivity: parseInt($("micSensitivity")?.value || "85", 10)
      };
    }

    if ($("locCity")) {
      payload.location = {
        city: $("locCity").value.trim()
      };
    }

    if ($("actShort") || $("rotaryInvert")) {
      const inp = {};
      if ($("rotaryInvert")) inp.rotaryInvert = $("rotaryInvert").checked;
      if ($("rotaryStep")) inp.rotaryStep = parseInt($("rotaryStep").value, 10) || 1;
      ["actShort", "actLong", "remoteShort", "remoteLong", "remoteDouble",
       "remoteLeftLong", "remoteRightLong", "remoteBackLong", "remotePlayDouble"].forEach((id) => {
        if ($(id)) {
          const cfgKey = id === "actShort" ? "actionShort" : (id === "actLong" ? "actionLong" : id);
          inp[cfgKey] = $(id).value;
        }
      });
      payload.input = inp;
    }

    if ($("astEnabled")) {
      const ast = {
        enabled: $("astEnabled").checked,
        name: ($("astName")?.value.trim()) || "Алиса",
        wakePhrase: getWakePhrase(),
        voiceName: $("astVoice")?.value || "Aoede",
        systemPrompt: $("astPrompt")?.value || "",
        wakeWordEnabled: $("astWakeEn") ? $("astWakeEn").checked : true,
        chimeEnabled: $("astChime") ? $("astChime").checked : true
      };
      const newGeminiKey = $("astApiKey")?.value.trim() || "";
      if (newGeminiKey) ast.geminiApiKey = newGeminiKey;
      if ($("astMode")) ast.mode = $("astMode").value;
      if ($("astQuickCommands")) ast.quickCommands = $("astQuickCommands").checked;
      ast.geminiModel = "gemini-3.8-live";
      if ($("astModel")) $("astModel").value = "gemini-3.8-live";
      if ($("astProxyHost")) ast.geminiProxyHost = $("astProxyHost").value.trim();
      if ($("astFollowUp")) ast.followUpSec = Number.isFinite(parseInt($("astFollowUp").value, 10)) ? parseInt($("astFollowUp").value, 10) : 4;
      if ($("astMaxSess")) ast.maxSessionSec = parseInt($("astMaxSess").value, 10) || 120;
      if ($("astEchoGuard")) ast.echoGuard = $("astEchoGuard").checked;
      if ($("astQuietWhisper")) ast.quietWhisperReply = $("astQuietWhisper").checked;
      if ($("astSearch")) ast.googleSearch = $("astSearch").checked;
      if ($("astAgentMode")) ast.agentMode = $("astAgentMode").checked;
      ast.memoryNotes = currentMemoryNotes;
      payload.assistant = ast;
    }

    if ($("wyoEn")) {
      payload.wyoming = {
        enabled: $("wyoEn").checked,
        deviceName: $("wyoName")?.value.trim() || "Station Max",
        vadThreshold: parseInt($("wyoVad")?.value ?? "450", 10) || 450,
        alwaysStream: $("wyoAlways") ? $("wyoAlways").checked : false
      };
    }

    if ($("mqttEn")) {
      payload.mqtt = {
        enabled: $("mqttEn").checked,
        host: $("mqttHost")?.value.trim() || "",
        port: parseInt($("mqttPort")?.value ?? "1883", 10) || 1883,
        username: $("mqttUser")?.value || "",
        baseTopic: $("mqttTopic")?.value.trim() || "openstation/station_max"
      };
      const newMqttPassword = $("mqttPass")?.value || "";
      if (newMqttPassword) payload.mqtt.password = newMqttPassword;
    }

    if ($("otaAutoCheck") || $("otaSyncBootanim")) {
      const ota = {};
      if ($("otaAutoCheck")) ota.autoCheck = $("otaAutoCheck").checked;
      if ($("otaSyncBootanim")) ota.syncBootanimation = $("otaSyncBootanim").checked;
      if ($("otaRepo")) ota.repo = $("otaRepo").value.trim() || "Olegu621/OPENSTATION";
      if ($("otaBranch")) ota.branch = $("otaBranch").value.trim() || "main";
      payload.ota = ota;
    }

    if ($("wifiAdbEnabled")) {
      payload.network = { wifiAdbEnabled: $("wifiAdbEnabled").checked };
    }

    try {
      const next = await api("/api/config", "POST", payload);
      applyTelemetry(next, false);
      if (notify) showToast("Настройки сохранены");
    } catch (e) {
      showToast("Ошибка сохранения: " + e.message, true);
    }
  }

  const ALICE_STATE_RU = {
    idle: "Спит",
    connecting: "Подключение...",
    listening: "Слушает",
    thinking: "Думает...",
    speaking: "Говорит",
    error: "Ошибка"
  };

  function updateAliceLiveBox(liveU, liveM) {
    const box = $("aliceLiveBox");
    if (!box) return;
    if (liveU || liveM) {
      box.style.display = "block";
      if ($("aliceLiveUser")) $("aliceLiveUser").textContent = liveU ? ("Вы: " + liveU) : "";
      if ($("aliceLiveModel")) $("aliceLiveModel").textContent = liveM ? ("Алиса: " + liveM) : "";
    } else {
      box.style.display = "none";
      if ($("aliceLiveUser")) $("aliceLiveUser").textContent = "";
      if ($("aliceLiveModel")) $("aliceLiveModel").textContent = "";
    }
  }

  function renderAliceHistory(hist) {
    const box = $("aliceHistory");
    if (!box) return;
    const sig = JSON.stringify(hist || []);
    if (sig === lastHistSig) return;
    lastHistSig = sig;
    box.innerHTML = "";
    if (!hist || !hist.length) {
      const h = document.createElement("div");
      h.className = "hint";
      h.textContent = "Скажите «Алиса...» или нажмите «Разбудить Алису».";
      box.appendChild(h);
      return;
    }
    hist.forEach((item) => {
      const div = document.createElement("div");
      div.className = "dialog-item " + (item.role || "model");
      const meta = document.createElement("div");
      meta.className = "dialog-meta";
      const rSpan = document.createElement("span");
      rSpan.textContent = item.role === "user" ? "Вы" : (item.role === "tool" ? "Действие" : "Алиса");
      const tSpan = document.createElement("span");
      tSpan.textContent = item.ts || "";
      meta.appendChild(rSpan);
      meta.appendChild(tSpan);
      const body = document.createElement("div");
      body.textContent = item.text || "";
      div.appendChild(meta);
      div.appendChild(body);
      box.appendChild(div);
    });
    box.scrollTop = box.scrollHeight;
  }

  function appendAliceHistoryItem(role, text) {
    const box = $("aliceHistory");
    if (!box || !text) return;
    if (box.querySelector(".hint")) box.innerHTML = "";
    const now = new Date();
    const ts = now.toTimeString().slice(0, 8);
    const div = document.createElement("div");
    div.className = "dialog-item " + (role || "model");
    const meta = document.createElement("div");
    meta.className = "dialog-meta";
    const rSpan = document.createElement("span");
    rSpan.textContent = role === "user" ? "Вы" : (role === "tool" ? "Действие" : "Алиса");
    const tSpan = document.createElement("span");
    tSpan.textContent = ts;
    meta.appendChild(rSpan);
    meta.appendChild(tSpan);
    const body = document.createElement("div");
    body.textContent = text;
    div.appendChild(meta);
    div.appendChild(body);
    box.appendChild(div);
    box.scrollTop = box.scrollHeight;
  }

  function applyMusicState(m) {
    m = m || {};
    const qb = $("btnQuickMusic");
    if (qb) {
      if (m.playing && m.title) {
        const shortT = m.title.length > 22 ? m.title.slice(0, 21) + "…" : m.title;
        qb.textContent = `♪ ${shortT}`;
        qb.classList.add("btn-accent");
      } else if (m.paused && m.title) {
        const shortT = m.title.length > 20 ? m.title.slice(0, 19) + "…" : m.title;
        qb.textContent = `⏸ ${shortT}`;
        qb.classList.remove("btn-accent");
      } else {
        qb.textContent = "🌊 Моя волна";
        qb.classList.remove("btn-accent");
      }
    }
    const ban = $("musicStatusBanner");
    if (ban) {
      const errRaw = m.error || "";
      const errClean = errRaw.replace(/^AUTH_REQUIRED:\s*/i, "");
      const acctStr = m.hasAuth ? `@${m.userLogin || "OK"}${m.hasPlus ? " · Плюс" : ""}` : "без входа";
      const trackStr = m.title ? `${m.artist || "?"} — ${m.title}` : "Нет активного трека";
      if (errClean) {
        ban.textContent = `⚠ ${errClean} (${acctStr})`;
        ban.className = "status-banner warn";
      } else if (m.buffering) {
        ban.textContent = `⏳ Загрузка: ${trackStr}`;
        ban.className = "status-banner warn";
      } else if (m.playing) {
        ban.textContent = `▶ ${m.sourceLabel || "Моя волна"}: ${trackStr} · ${acctStr}`;
        ban.className = "status-banner ok";
      } else if (m.paused && m.title) {
        ban.textContent = `⏸ Пауза: ${trackStr} · ${acctStr}`;
        ban.className = "status-banner";
      } else {
        ban.textContent = `Готово (${acctStr}) · Нажмите «Моя волна» или введите запрос`;
        ban.className = "status-banner " + (m.hasAuth ? "ok" : "");
      }
    }
    const tt = $("playerTrackTitle");
    if (tt) tt.textContent = m.title || "Моя волна";
    const ta = $("playerTrackArtist");
    if (ta) ta.textContent = m.artist || (m.sourceLabel || "Яндекс Музыка");
    const cov = $("playerCoverImg");
    if (cov && m.coverUrl) {
      cov.src = m.coverUrl;
    }
    const pi = $("playerPlayIcon");
    if (pi) {
      if (m.playing) {
        pi.innerHTML = '<path fill="white" d="M144 479H48c-26.5 0-48-21.5-48-48V79c0-26.5 21.5-48 48-48h96c26.5 0 48 21.5 48 48v352c0 26.5-21.5 48-48 48zm304-48V79c0-26.5-21.5-48-48-48h-96c-26.5 0-48 21.5-48 48v352c0 26.5 21.5 48 48 48h96c26.5 0 48-21.5 48-48z"/>';
      } else {
        pi.innerHTML = '<path fill="white" d="M424.4 214.7L72.4 6.6C43.8-10.3 0 6.1 0 47.9V464c0 37.5 40.7 60.1 72.4 41.3l352-208c31.4-18.5 31.5-64.1 0-82.6z" />';
      }
    }
    const lk = $("btnMusicLike");
    if (lk) {
      lk.classList.toggle("liked", !!m.liked);
    }

    document.querySelectorAll('[data-mini="title"]').forEach((e) => { e.textContent = m.title || "Моя волна"; });
    document.querySelectorAll('[data-mini="artist"]').forEach((e) => { e.textContent = m.artist || (m.sourceLabel || "Яндекс Музыка"); });
    if (m.coverUrl) {
      document.querySelectorAll('[data-mini="cover"]').forEach((e) => {
        if (e.src !== m.coverUrl) e.src = m.coverUrl;
      });
    }
    const playPath = m.playing ? "M6 5h4v14H6zM14 5h4v14h-4z" : "M7 4l13 8-13 8z";
    document.querySelectorAll('[data-mini="play"] path').forEach((e) => e.setAttribute("d", playPath));
    const pct = m.durationMs > 0 ? Math.min(100, (100 * (m.positionMs || 0)) / m.durationMs) : 0;
    document.querySelectorAll('[data-mini="progress"]').forEach((e) => { e.style.width = pct.toFixed(1) + "%"; });
    document.body.classList.toggle("no-track", !m.title);

    if ("hasAuth" in m) {
      if ($("musicLoginCard")) $("musicLoginCard").hidden = !!m.hasAuth;
      if ($("musicMain")) $("musicMain").hidden = !m.hasAuth;
    }
  }

  function applyBootanimState(ba) {
    ba = ba || {};
    const mode = ba.mode || "missing";
    const modeBadge = $("bootanimModeBadge");
    if (modeBadge) {
      const labels = {
        openstation: ["OpenStation Max", "badge ok"],
        stock: ["Оригинал Яндекс", "badge"],
        custom: ["Своя заставка", "badge warn"],
        missing: ["Не установлена", "badge warn"]
      };
      const [txt, cls] = labels[mode] || ["Неизвестно", "badge"];
      modeBadge.textContent = txt;
      modeBadge.className = cls;
    }

    const ban = $("bootanimStatusBanner");
    if (ban) {
      const mb = typeof ba.size === "number" && ba.size > 0
        ? ` · ${(ba.size / (1024 * 1024)).toFixed(1)} МБ`
        : "";
      const syncNote = ba.syncWithOta
        ? " · обновляется вместе с OTA"
        : " · защищена от перезаписи при OTA";
      if (mode === "openstation") {
        ban.textContent = `Активна фирменная заставка OpenStation Max${mb}${syncNote}`;
        ban.className = "status-banner ok";
      } else if (mode === "stock") {
        ban.textContent = `Активна оригинальная заставка Яндекс${mb}${syncNote}`;
        ban.className = "status-banner";
      } else if (mode === "custom") {
        ban.textContent = `Установлена ваша заставка${mb}${syncNote}`;
        ban.className = "status-banner";
      } else {
        ban.textContent = "Файл заставки отсутствует на устройстве";
        ban.className = "status-banner warn";
      }
    }

    if ($("otaSyncBootanim") && document.activeElement !== $("otaSyncBootanim") && typeof ba.syncWithOta === "boolean") {
      $("otaSyncBootanim").checked = ba.syncWithOta;
    }

    const dis = bootanimBusy;
    if ($("btnBootanimOta")) $("btnBootanimOta").disabled = dis;
    if ($("btnBootanimUpload")) $("btnBootanimUpload").disabled = dis;
    if ($("btnBootanimRestore")) {
      $("btnBootanimRestore").disabled = dis || (!ba.hasStockBackup && mode !== "stock");
    }
    if ($("btnBootanimPreview")) $("btnBootanimPreview").disabled = dis || mode === "missing";
  }

  function applyWifiAdbState(wa, d) {
    wa = wa || {};
    const netCfg = d?.config?.network || {};
    const enabled = (wa.enabled !== undefined) ? !!wa.enabled : (netCfg.wifiAdbEnabled !== false);
    const port = wa.port || netCfg.wifiAdbPort || 5555;
    const listening = !!wa.listening;
    const busy = !!(wifiAdbBusy || wa.busy);
    const ipStr = d?.ip && d.ip !== "127.0.0.1" ? d.ip : "";
    const hostStr = d?.hostname || "station-max";
    const cmd = wa.connectCommand || (ipStr ? `adb connect ${ipStr}:${port}` : (wa.mdnsCommand || `adb connect ${hostStr}.local:${port}`));

    const bdg = $("wifiAdbBadge");
    if (bdg) {
      if (busy) {
        bdg.textContent = "Обновление...";
        bdg.className = "badge warn";
      } else if (!enabled) {
        bdg.textContent = "Выключен (USB)";
        bdg.className = "badge";
      } else if (listening) {
        bdg.textContent = `Активен :${port}`;
        bdg.className = "badge ok";
      } else {
        bdg.textContent = "Перезапуск...";
        bdg.className = "badge warn";
      }
    }

    const ban = $("wifiAdbStatusBanner");
    if (ban) {
      if (busy) {
        ban.textContent = "⏳ Выполняется настройка службы Wi-Fi ADB...";
        ban.className = "status-banner warn";
      } else if (!enabled) {
        ban.textContent = "Wi-Fi ADB выключен (отладка работает только по USB)";
        ban.className = "status-banner";
      } else if (listening) {
        const pwNote = wa.wifiPowerSaveState === "off"
          ? " · Wi-Fi энергосбережение: выкл"
          : (wa.wifiPowerSaveState === "on" ? " · Wi-Fi энергосбережение: вкл" : "");
        ban.textContent = `✓ Готов: ${cmd}${pwNote}`;
        ban.className = "status-banner ok";
      } else {
        const r = wa.lastRepairReason ? ` (${wa.lastRepairReason})` : "";
        ban.textContent = `⏳ Запуск порта :${port}${r}...`;
        ban.className = "status-banner warn";
      }
    }

    if ($("wifiAdbCmd") && document.activeElement !== $("wifiAdbCmd")) {
      $("wifiAdbCmd").value = cmd;
    }
    if ($("wifiAdbCmdRow")) {
      $("wifiAdbCmdRow").style.display = enabled ? "" : "none";
    }
    if ($("wifiAdbEnabled")) {
      if (!busy && document.activeElement !== $("wifiAdbEnabled")) {
        $("wifiAdbEnabled").checked = enabled;
      }
      $("wifiAdbEnabled").disabled = busy;
    }
    if ($("btnRestartWifiAdb")) $("btnRestartWifiAdb").disabled = busy;
  }

  function applyOtaState(ota, fwVer) {
    ota = ota || {};
    const instVer = ota.installedVersion || fwVer || "1.5.0";
    if ($("otaVersionBadge")) {
      $("otaVersionBadge").textContent = `v${instVer}`;
    }
    const hdrBtn = $("badgeOtaUpdate");
    if (hdrBtn) {
      if (ota.updateAvailable && ota.latestVersion) {
        hdrBtn.style.display = "inline-flex";
        hdrBtn.textContent = `⬇ v${ota.latestVersion}`;
      } else {
        hdrBtn.style.display = "none";
      }
    }
    const ban = $("otaStatusBanner");
    if (ban) {
      const st = ota.state || "idle";
      const changedRu = Array.isArray(ota.changedComponents)
        ? ota.changedComponents.map((c) => COMPONENT_RU[c] || c)
        : [];
      const changedList = changedRu.length ? ` (${changedRu.join(", ")})` : "";
      if (st === "checking") {
        ban.textContent = `⏳ ${ota.progressText || "Проверка обновлений..."}`;
        ban.className = "status-banner warn";
      } else if (st === "downloading" || st === "verifying" || st === "installing" || st === "restarting") {
        ban.textContent = `⬇ ${ota.progressText || "Обновление прошивки..."}`;
        ban.className = "status-banner live";
      } else if (st === "available" || ota.updateAvailable) {
        ban.textContent = `✨ Доступно обновление v${ota.latestVersion || "?"}${changedList}`;
        ban.className = "status-banner warn";
      } else if (st === "up_to_date") {
        ban.textContent = `✓ ${ota.progressText || ("Установлена последняя версия v" + instVer)}`;
        ban.className = "status-banner ok";
      } else if (st === "error") {
        ban.textContent = `✗ ${ota.error || ota.progressText || "Ошибка обновления"}`;
        ban.className = "status-banner err";
      } else {
        ban.textContent = `Установлена версия v${instVer}`;
        ban.className = "status-banner";
      }
    }
    const pw = $("otaProgressWrap");
    const pf = $("otaProgressFill");
    const pl = $("otaProgressLabel");
    const inProg = !!(ota.busy || ["checking", "downloading", "verifying", "installing", "restarting"].includes(ota.state));
    if (pw) {
      pw.style.display = inProg ? "block" : "none";
      if (pf) pf.style.width = `${Math.max(4, Math.min(100, ota.progressPct || 0))}%`;
      if (pl) pl.textContent = `${ota.progressPct || 0}% · ${ota.progressText || ""}`;
    }
    const cl = $("otaChangelogBox");
    if (cl) {
      if (ota.changelog) {
        cl.style.display = "block";
        cl.textContent = `Что нового (v${ota.latestVersion || instVer}): ${ota.changelog}`;
      } else {
        cl.style.display = "none";
      }
    }
    if ($("btnOtaCheck")) $("btnOtaCheck").disabled = !!ota.busy;
    if ($("btnOtaInstall")) {
      $("btnOtaInstall").disabled = !!ota.busy;
      $("btnOtaInstall").style.display = (ota.updateAvailable && !ota.busy) ? "inline-flex" : "none";
      if (ota.latestVersion) $("btnOtaInstall").textContent = `⬇ Установить v${ota.latestVersion}`;
    }
    if ($("btnOtaReinstall")) {
      $("btnOtaReinstall").disabled = !!ota.busy;
      $("btnOtaReinstall").style.display = (!ota.updateAvailable && !ota.busy) ? "inline-flex" : "none";
    }
  }

  let lastWakeWordStatus = {};

  function getWakePhrase() {
    const active = document.querySelector("#astWakePhrasePresets .seg.active")
      || document.querySelector("#wizWakePhrasePresets .seg.active");
    return (active && active.dataset.wphrase === "yandex") ? "yandex" : "alisa";
  }

  function syncWakePhraseUI(phrase) {
    const p = phrase === "yandex" ? "yandex" : "alisa";
    document.querySelectorAll("#astWakePhrasePresets .seg, #wizWakePhrasePresets .seg").forEach((b) => {
      b.classList.toggle("active", (b.dataset.wphrase || "alisa") === p);
    });
  }

  function syncWakePhraseVisibility(showRow) {
    const show = showRow ? "" : "none";
    if ($("astWakePhraseRow")) $("astWakePhraseRow").style.display = show;
    if ($("wizWakePhraseRow")) $("wizWakePhraseRow").style.display = show;
  }

  function syncAssistantNameLabels(name, ww) {
    const clean = (name || "").trim() || "Алиса";
    const wwState = ww || lastWakeWordStatus || {};
    const isAlisa = clean.toLowerCase() === "алиса" || clean.toLowerCase() === "alisa";
    const avail = Array.isArray(wwState.availablePhrases) ? wwState.availablePhrases : [];
    const hasStock = isAlisa && avail.length > 0;
    const stockActive = isAlisa && wwState.engine === "stock";
    const stockFallback = isAlisa && !!wwState.stockFallback;
    const phraseName = (stockActive && getWakePhrase() === "yandex") ? "Яндекс" : clean;
    syncWakePhraseVisibility(hasStock);
    document.querySelectorAll(".js-ast-name").forEach((el) => { el.textContent = clean; });
    if ($("sideTabAlice")) $("sideTabAlice").dataset.title = clean;
    if ($("mobTabAlice")) $("mobTabAlice").dataset.title = clean;
    if ($("aliceCardTitle")) $("aliceCardTitle").textContent = `Голосовой ассистент «${clean}»`;
    if ($("astSettingsTitle")) $("astSettingsTitle").textContent = `Настройки ассистента «${clean}»`;
    if ($("btnAliceTrigger")) $("btnAliceTrigger").textContent = `Разбудить (${clean})`;
    if ($("btnWizTestSay")) $("btnWizTestSay").textContent = `🎤 Разбудить (${clean})`;
    if ($("aliceTextPrompt")) $("aliceTextPrompt").placeholder = `Написать «${clean}» текстом (ответит голосом)...`;
    if ($("aliceEmptyHint")) $("aliceEmptyHint").textContent = `Скажите «${phraseName}...» или нажмите «Разбудить (${clean})».`;
    if ($("wizWakeLabel")) {
      $("wizWakeLabel").textContent = stockActive
        ? `Откликаться на «${phraseName}» (штатный Yandex-споттер)`
        : (stockFallback
          ? `Откликаться на слово «${clean}» (резерв OpenStation KWS)`
          : `Откликаться на слово «${clean}»`);
    }
    if ($("tmVoiceHint")) $("tmVoiceHint").textContent = `Или голосом: «${phraseName}, разбуди меня в 7:30 по будням».`;
    if ($("haVoiceHint")) $("haVoiceHint").textContent = `Устройства синхронизируются с Home Assistant. Можно сказать: «${phraseName}, выключи свет в гостиной».`;
    if ($("astNameHint")) {
      const engNote = stockActive
        ? `Используется штатный Yandex-споттер («${phraseName}»).`
        : (stockFallback
          ? "Штатный споттер недоступен — включён резервный OpenStation KWS."
          : "Используется OpenStation KWS.");
      $("astNameHint").textContent = `Ассистент откликается на имя «${clean}» и автоматически подбирает стиль и голос. ${engNote}`;
    }
    document.querySelectorAll("#astNamePresets .seg, #wizNamePresets .seg").forEach((b) => {
      b.classList.toggle("active", (b.dataset.aname || "").toLowerCase() === clean.toLowerCase());
    });
    if ($("pageTitle") && document.body.classList.contains("tab-alice")) {
      $("pageTitle").textContent = clean;
    }
  }

  function micInputLabel(engine) {
    const backend = engine?.backend || "";
    if (backend === "paused_for_app") return "Захват приостановлен";
    if (backend === "alsa_unavailable" || backend === "unavailable") return "Микрофон недоступен";
    if (!(engine?.channels > 0)) return "Ожидание аудиовхода";
    const rate = engine.sampleRate ? " · " + (engine.sampleRate / 1000) + " кГц" : "";
    if (backend.endsWith("_vqe")) return "Yandex VQE" + rate;
    return "Запасной вход " + (backend.startsWith("alsa") ? "ALSA" : "AAudio") + rate;
  }

  function applyAliceBanner(astState, ast, cfgAst) {
    const cleanName = (cfgAst?.name || "").trim() || "Алиса";
    const ww = ast?.wakeWord || {};
    lastWakeWordStatus = ww;
    syncWakePhraseUI(cfgAst?.wakePhrase || "alisa");
    syncAssistantNameLabels(cleanName, ww);
    const isAlisa = cleanName.toLowerCase() === "алиса" || cleanName.toLowerCase() === "alisa";
    const stockActive = isAlisa && ww.engine === "stock";
    const phraseName = (stockActive && (ww.phrase === "яндекс" || getWakePhrase() === "yandex")) ? "Яндекс" : cleanName;
    const stRu = ALICE_STATE_RU[astState] || astState;
    const qb = $("btnQuickAlice");
    if (qb) {
      qb.textContent = `${cleanName}: ${stRu}`;
      qb.classList.toggle("active", !!(ast?.active));
    }
    const avail = Array.isArray(ww.availablePhrases) ? ww.availablePhrases : null;
    document.querySelectorAll("#astWakePhrasePresets .seg, #wizWakePhrasePresets .seg").forEach((b) => {
      const key = (b.dataset.wphrase === "yandex") ? "яндекс" : "алиса";
      const ok = !avail || avail.includes(key);
      b.disabled = !ok;
      b.style.opacity = ok ? "" : "0.45";
      b.title = ok ? "" : "Модель этого слова не установлена";
    });
    const bww = $("badgeWakeWord");
    if (bww) {
      bww.textContent = ww.ready
        ? (ww.stockFallback ? "Голосовой запуск: ВКЛ (резерв KWS)" : "Голосовой запуск: ВКЛ")
        : "Голосовой запуск: выкл";
      bww.className = "badge " + (ww.ready ? (ww.stockFallback ? "warn" : "ok") : "warn");
    }
    const agentOn = (ast && typeof ast.agentMode === "boolean") ? ast.agentMode : !!cfgAst?.agentMode;
    const bag = $("badgeAgentMode");
    if (bag) {
      bag.textContent = agentOn ? "Агент ADB: ВКЛ" : "Агент ADB: выкл";
      bag.className = "badge " + (agentOn ? "live" : "");
    }
    const ban = $("aliceStatusBanner");
    if (ban) {
      if (ast?.lastError) {
        ban.textContent = `Ошибка: ${ast.lastError}`;
        ban.className = "status-banner err";
      } else if (cfgAst && !cfgAst.geminiApiKeySet && !cfgAst.geminiApiKey && cfgAst.mode === "gemini") {
        ban.textContent = "Укажите Gemini API Key справа и нажмите «Применить и сохранить»";
        ban.className = "status-banner warn";
      } else if (ast?.active) {
        ban.textContent = `${cleanName}: ${stRu}`;
        ban.className = "status-banner live";
      } else {
        ban.textContent = ww.ready
          ? `Готова — скажите «${phraseName}» или нажмите кнопку ниже`
          : `Готова к запуску кнопкой (отклик на фразу «${phraseName}» выключен)`;
        ban.className = "status-banner " + (ww.ready ? "ok" : "");
      }
    }
  }

  function formatTrimLabel(trimSteps) {
    const t = Number.isFinite(trimSteps) ? trimSteps : 0;
    const db = (t * 0.5).toFixed(1);
    const sign = t >= 0 ? "+" : "";
    return `${sign}${db} дБ`;
  }

  function applyTelemetry(d, populateInputs) {
    if (!d) return;
    state = d;
    const hw = d.hardware || {};
    const cfg = d.config || {};
    const au = d.audio || {};
    const ast = d.assistant || {};
    const wyo = d.wyoming || {};
    const mq = d.mqtt || {};

    if ($("sysSubtitle")) {
      $("sysSubtitle").textContent = `${d.ip || "local"} · ${d.hostname || "station-max"}.local`;
    }
    if (typeof au.volumePercent === "number") {
      if ($("badgeVol")) $("badgeVol").textContent = `Громкость: ${au.volumePercent}%`;
      if (!volTimer && $("sysVol") && document.activeElement !== $("sysVol")) {
        $("sysVol").value = au.volumePercent;
        if ($("lblSysVol")) $("lblSysVol").textContent = `${au.volumePercent}%`;
      }
    }

    if ($("ringBrightnessVal")) {
      const briPct = typeof hw.effectiveBrightness === "number"
        ? Math.round((hw.effectiveBrightness / 255) * 100) + "%"
        : "--%";
      $("ringBrightnessVal").textContent = briPct;
    }

    if ($("fb5StatusBanner")) {
      const rec = typeof hw.fb5Recoveries === "number" ? hw.fb5Recoveries : 0;
      if (hw.fb5Present) {
        $("fb5StatusBanner").textContent = rec > 0
          ? `Экранчик часов активен · автовосстановлений: ${rec}`
          : "Экранчик часов активен";
        $("fb5StatusBanner").className = "status-banner ok";
      } else {
        $("fb5StatusBanner").textContent = rec > 0
          ? `Передний экранчик восстанавливается (${rec})...`
          : "Передний экранчик отключён";
        $("fb5StatusBanner").className = "status-banner warn";
      }
    }

    const muted = cfg.audio?.micMuted;
    if ($("btnQuickMute")) {
      $("btnQuickMute").textContent = muted ? "Микрофон: ВЫКЛ" : "Микрофон: ВКЛ";
      $("btnQuickMute").className = "btn btn-sm btn-danger" + (muted ? " active" : "");
    }

    const bt = d.bluetooth || {};
    if ($("btnQuickBt")) {
      const btOn = !!(bt.enabled);
      const btDisc = !!(bt.discoverable);
      if (btDisc) {
        const rem = bt.discoverableRemainingSec ?? 0;
        $("btnQuickBt").textContent = rem > 0 ? `ᛒ BT: Виден (${rem}с)` : "ᛒ BT: Виден";
        $("btnQuickBt").className = "btn btn-sm btn-accent active";
      } else if (btOn) {
        $("btnQuickBt").textContent = "ᛒ BT: ВКЛ";
        $("btnQuickBt").className = "btn btn-sm btn-accent";
      } else {
        $("btnQuickBt").textContent = "ᛒ BT: ВЫКЛ";
        $("btnQuickBt").className = "btn btn-sm";
      }
    }
    if ($("btnRemoteBtToggle")) {
      const btOn = !!(bt.enabled);
      const btDisc = !!(bt.discoverable);
      $("btnRemoteBtToggle").textContent = btDisc
        ? "ᛒ BT-колонка: Видна"
        : (btOn ? "ᛒ BT-колонка: ВКЛ" : "ᛒ BT-колонка: ВЫКЛ");
      $("btnRemoteBtToggle").className = "btn btn-sm" + (btOn ? " btn-accent" : "");
    }

    // Mic & VQE DSP monitor
    const eng = au.engine || {};
    const monoRms = typeof eng.monoRms === "number" ? eng.monoRms : 0;
    const noiseFloor = typeof eng.noiseFloor === "number" ? eng.noiseFloor : 0;
    const vadActive = !!(ast.wakeWord?.vadSpeech);

    if ($("badgeMicDsp")) {
      $("badgeMicDsp").textContent = micInputLabel(eng);
    }
    if ($("metricMonoRms")) {
      $("metricMonoRms").textContent = `${Math.round(monoRms)} RMS`;
    }
    if ($("metricNoiseFloor")) {
      $("metricNoiseFloor").textContent = `${Math.round(noiseFloor)} RMS`;
    }
    if ($("metricVad")) {
      $("metricVad").textContent = vadActive ? "🗣 Слышу голос" : "Тишина";
      $("metricVad").style.color = vadActive ? "#10b981" : "#94a3b8";
    }
    if ($("vuMeterFill")) {
      let pct = 0;
      if (monoRms > 0) {
        pct = Math.min(100, Math.max(2, Math.round((Math.log10(monoRms + 1) / Math.log10(30000)) * 100)));
      }
      $("vuMeterFill").style.width = `${pct}%`;
      if ($("vuMeterPeak")) {
        $("vuMeterPeak").style.left = `${pct}%`;
      }
    }
    if ($("micDiagHint")) {
      const mixer = au.mixer || {};
      const capErr = eng.captureError || "";
      const playErr = eng.playbackError || "";
      const noCard = mixer.cardReady === false || eng.pcmCardPresent === false || capErr === "no_pcm_device";
      let diagMsg = "";
      if (noCard) {
        diagMsg = "Нет звуковой карты AML-AUGESOUND (/dev/snd/pcmC0* отсутствует): проверьте ревизию misc/DTB (YNDX-0008 rev1/2 TI vs SY6045S rev3/4/5) или шлейф/питание платы микрофонов и усилителей.";
      } else if (eng.backend === "alsa_unavailable" && capErr === "device_busy") {
        diagMsg = "Микрофон занят зависшей аудиослужбой Android (EBUSY). Выполняется автовосстановление audio-hal/audioserver...";
      } else if (playErr) {
        diagMsg = `Ошибка аудиовыхода (${playErr}): AAudio и резервный ALSA-выход недоступны.`;
      }
      $("micDiagHint").textContent = diagMsg;
      $("micDiagHint").style.display = diagMsg ? "" : "none";
    }

    // Update 24 SVG LEDs
    const px = hw.ringPixels || [];
    for (let i = 0; i < dots.length && i < px.length; i++) {
      const [r, g, b] = px[i];
      dots[i].setAttribute("fill", `rgb(${r},${g},${b})`);
    }

    if ($("wyoStatusBanner")) {
      $("wyoStatusBanner").textContent = wyo.clientConnected
        ? `Home Assistant подключён (${wyo.peerIp})${wyo.streaming ? " · идёт речь" : ""}`
        : "Ожидание подключения Home Assistant";
      $("wyoStatusBanner").className = "status-banner " + (wyo.clientConnected ? "ok" : "warn");
    }

    if ($("mqttStatusBanner")) {
      $("mqttStatusBanner").textContent = mq.enabled
        ? (mq.connected ? `Подключено к ${mq.host}:${mq.port}` : `Подключение к ${mq.host}:${mq.port}...`)
        : "MQTT отключён";
      $("mqttStatusBanner").className = "status-banner " + (mq.connected ? "ok" : "");
    }

    applyAliceBanner(ast.state || "idle", ast, cfg.assistant);
    applyMusicState(d.music || {});
    renderTimers(d);
    applyBootanimState(d.bootanim || {});
    applyOtaState(d.ota || {}, d.firmwareVersion);
    applyWifiAdbState(d.wifiAdb || {}, d);
    if (typeof handleMiscRepairState === "function") handleMiscRepairState(d.miscRepair);

    if (otaPendingRestart && !d.ota?.busy && d.ota?.state !== "restarting") {
      otaPendingRestart = false;
      showToast("Прошивка OpenStation Max v" + (d.firmwareVersion || "1.6.3") + " активна!");
      if (d.postOtaWizardPending && $("wizardModal")?.style.display !== "flex") {
        openWizard(1);
      }
    }
    updateAliceLiveBox(ast.liveUserText || "", ast.liveModelText || "");
    if (Array.isArray(ast.history)) {
      renderAliceHistory(ast.history);
    }
    syncWizardTelemetry(d, populateInputs);

    if (populateInputs && cfg.ledRing) {
      document.querySelectorAll("#ringModes .seg").forEach((b) => {
        b.classList.toggle("active", b.dataset.mode === cfg.ledRing.mode);
      });
      if ($("ringColor")) $("ringColor").value = cfg.ledRing.color || "#00d4ff";
      if ($("ringSecColor")) $("ringSecColor").value = cfg.ledRing.secondaryColor || "#7c3aed";
      if ($("ringBrightness")) {
        $("ringBrightness").value = cfg.ledRing.brightness ?? 160;
        if ($("lblBrightness")) $("lblBrightness").textContent = $("ringBrightness").value;
      }
      if ($("ringAutoBrightness")) $("ringAutoBrightness").checked = !!cfg.ledRing.autoBrightness;
      if ($("ringMinAuto")) {
        $("ringMinAuto").value = cfg.ledRing.minAutoBrightness ?? 18;
        if ($("lblMinAuto")) $("lblMinAuto").textContent = $("ringMinAuto").value;
      }
      if ($("ringMaxAuto")) {
        $("ringMaxAuto").value = cfg.ledRing.maxAutoBrightness ?? 255;
        if ($("lblMaxAuto")) $("lblMaxAuto").textContent = $("ringMaxAuto").value;
      }
      if ($("ringSpeed")) {
        $("ringSpeed").value = cfg.ledRing.speed ?? 55;
        if ($("lblSpeed")) $("lblSpeed").textContent = $("ringSpeed").value;
      }

      if ($("scrMode")) $("scrMode").value = cfg.ledScreen?.mode || "clock";
      if ($("scrText")) $("scrText").value = cfg.ledScreen?.text || "OPEN STATION MAX";
      if ($("scrColor")) $("scrColor").value = cfg.ledScreen?.color || "#ffffff";
      if ($("clockStyle") && cfg.ledScreen?.clockStyle) $("clockStyle").value = cfg.ledScreen.clockStyle;
      if ($("visualizerStyle")) {
        const vs = cfg.ledScreen?.visualizerStyle || "pulse_bars";
        const sel = $("visualizerStyle");
        sel.value = Array.from(sel.options).some((o) => o.value === vs) ? vs : "pulse_bars";
      }
      if ($("autoMusicVisualizer")) $("autoMusicVisualizer").checked = cfg.ledScreen?.autoMusicVisualizer !== false;
      if ($("scrBri")) {
        $("scrBri").value = cfg.ledScreen?.brightness ?? 100;
        if ($("lblScrBri")) $("lblScrBri").textContent = $("scrBri").value;
      }
      if ($("scrAutoBri")) $("scrAutoBri").checked = !!cfg.ledScreen?.autoBrightness;
      if ($("scrSpeed")) {
        $("scrSpeed").value = cfg.ledScreen?.scrollSpeed ?? 45;
        if ($("lblScrSpeed")) $("lblScrSpeed").textContent = $("scrSpeed").value;
      }

      document.querySelectorAll("#audioRoutes .seg").forEach((b) => {
        b.classList.toggle("active", b.dataset.route === cfg.audio?.outputRoute);
      });
      const wTrim = cfg.audio?.wooferTrim ?? 0;
      const tTrim = cfg.audio?.tweetersTrim ?? 0;
      const hTrim = cfg.audio?.headphoneTrim ?? 0;
      if ($("wooferVol")) $("wooferVol").value = wTrim;
      if ($("lblWooferVol")) $("lblWooferVol").textContent = formatTrimLabel(wTrim);
      if ($("tweetersVol")) $("tweetersVol").value = tTrim;
      if ($("lblTweetersVol")) $("lblTweetersVol").textContent = formatTrimLabel(tTrim);
      if ($("hpVol")) $("hpVol").value = hTrim;
      if ($("lblHpVol")) $("lblHpVol").textContent = formatTrimLabel(hTrim);
      if ($("eqPreset")) $("eqPreset").value = cfg.audio?.eqPreset || "balanced";

      if ($("bassBoost")) {
        $("bassBoost").value = cfg.audio?.bassBoost ?? 20;
        if ($("lblBassBoost")) $("lblBassBoost").textContent = $("bassBoost").value + "%";
      }
      if ($("micSensitivity")) {
        $("micSensitivity").value = cfg.audio?.micSensitivity ?? 85;
        if ($("lblMicMicSens") || $("lblMicSens")) ($("lblMicSens") || $("lblMicMicSens")).textContent = $("micSensitivity").value + "%";
      }
      if (Array.isArray(cfg.audio?.eqBands) && cfg.audio.eqBands.length >= 10) {
        currentEqBands = cfg.audio.eqBands.slice(0, 10);
        updateEqSliders();
        renderEqCurve();
      }

      if ($("locCity")) {
        $("locCity").value = cfg.location?.city || "";
        $("locCity").placeholder = cfg.location?.resolvedCity
          ? `Автоматически: ${cfg.location.resolvedCity}` : "Автоопределение по IP...";
      }

      if ($("actShort")) $("actShort").value = cfg.input?.actionShort || "assistant";
      if ($("actLong")) $("actLong").value = cfg.input?.actionLong || "led_cycle";
      if ($("remoteShort")) $("remoteShort").value = cfg.input?.remoteShort || "assistant";
      if ($("remoteLong")) $("remoteLong").value = cfg.input?.remoteLong || "next";
      if ($("remoteDouble")) $("remoteDouble").value = cfg.input?.remoteDouble || "play_pause";
      if ($("remoteLeftLong")) $("remoteLeftLong").value = cfg.input?.remoteLeftLong || "prev";
      if ($("remoteRightLong")) $("remoteRightLong").value = cfg.input?.remoteRightLong || "next";
      if ($("remoteBackLong")) $("remoteBackLong").value = cfg.input?.remoteBackLong || "stop_alarm";
      if ($("remotePlayDouble")) $("remotePlayDouble").value = cfg.input?.remotePlayDouble || "next";
      if ($("rotaryInvert")) $("rotaryInvert").checked = !!cfg.input?.rotaryInvert;
      if ($("rotaryStep")) $("rotaryStep").value = cfg.input?.rotaryStep ?? 1;
      if ($("devHost")) $("devHost").value = cfg.deviceHostname || "station-max";

      if (cfg.assistant) {
        if ($("astEnabled")) $("astEnabled").checked = !!cfg.assistant.enabled;
        if ($("astName") && document.activeElement !== $("astName")) {
          $("astName").value = cfg.assistant.name || "Алиса";
        }
        if ($("astMode")) $("astMode").value = cfg.assistant.mode || "gemini";
        if ($("astQuickCommands")) $("astQuickCommands").checked = cfg.assistant.quickCommands !== false;
        if ($("astApiKey")) {
          $("astApiKey").value = "";
          $("astApiKey").placeholder = cfg.assistant.geminiApiKeySet ? "Ключ уже сохранён; пустое поле оставляет его без изменений" : "Вставьте Gemini API key";
        }
        if ($("astModel")) $("astModel").value = "gemini-3.8-live";
        if ($("astProxyHost")) {
          $("astProxyHost").value =
            cfg.assistant.geminiProxyHost !== undefined ? cfg.assistant.geminiProxyHost : "smart-auto";
        }
        if ($("astVoice")) $("astVoice").value = cfg.assistant.voiceName || "Aoede";
        if ($("astPrompt")) $("astPrompt").value = cfg.assistant.systemPrompt || "";
        syncPromptPresetButtons(cfg.assistant.systemPrompt || "");
        if ($("astWakeEn")) $("astWakeEn").checked = cfg.assistant.wakeWordEnabled !== false;
        syncWakePhraseUI(cfg.assistant.wakePhrase || "alisa");
        if ($("astChime")) $("astChime").checked = cfg.assistant.chimeEnabled !== false;
        if ($("astSearch")) $("astSearch").checked = cfg.assistant.googleSearch === true;
        if ($("astEchoGuard")) $("astEchoGuard").checked = cfg.assistant.echoGuard !== false;
        if ($("astQuietWhisper")) $("astQuietWhisper").checked = cfg.assistant.quietWhisperReply === true;
        if ($("astAgentMode")) $("astAgentMode").checked = cfg.assistant.agentMode !== false;
        if ($("astFollowUp")) $("astFollowUp").value = cfg.assistant.followUpSec ?? 4;
        if ($("astMaxSess")) $("astMaxSess").value = cfg.assistant.maxSessionSec ?? 120;
        if (Array.isArray(cfg.assistant.memoryNotes)) {
          currentMemoryNotes = cfg.assistant.memoryNotes.slice();
          renderMemoryNotes();
        }
      }

      if (cfg.homeAssistant) {
        if ($("haUrl") && document.activeElement !== $("haUrl") && cfg.homeAssistant.url) {
          $("haUrl").value = cfg.homeAssistant.url;
        }
        if ($("haToken")) {
          $("haToken").value = "";
          $("haToken").placeholder = cfg.homeAssistant.tokenSet
            ? "Токен сохранён; пустое поле оставляет его без изменений"
            : "eyJ…";
        }
      }

      if ($("wyoEn")) $("wyoEn").checked = !!cfg.wyoming?.enabled;
      if ($("wyoName")) $("wyoName").value = cfg.wyoming?.deviceName || "Station Max";
      if ($("wyoVad")) $("wyoVad").value = cfg.wyoming?.vadThreshold ?? 450;
      if ($("wyoAlways")) $("wyoAlways").checked = !!cfg.wyoming?.alwaysStream;

      if ($("mqttEn")) $("mqttEn").checked = !!cfg.mqtt?.enabled;
      if ($("mqttHost")) $("mqttHost").value = cfg.mqtt?.host || "";
      if ($("mqttPort")) $("mqttPort").value = cfg.mqtt?.port ?? 1883;
      if ($("mqttUser")) $("mqttUser").value = cfg.mqtt?.username || "";
      if ($("mqttPass")) {
        $("mqttPass").value = "";
        $("mqttPass").placeholder = cfg.mqtt?.passwordConfigured ? "Пароль сохранён; пустое поле оставляет его без изменений" : "Пароль MQTT";
      }
      if ($("mqttTopic")) $("mqttTopic").value = cfg.mqtt?.baseTopic || "openstation/station_max";
      if ($("otaRepo")) $("otaRepo").value = cfg.ota?.repo || "Olegu621/OPENSTATION";
      if ($("otaBranch")) $("otaBranch").value = cfg.ota?.branch || "main";
      if ($("otaAutoCheck")) $("otaAutoCheck").checked = cfg.ota?.autoCheck !== false;
      if ($("otaSyncBootanim")) $("otaSyncBootanim").checked = cfg.ota?.syncBootanimation !== false;
    } else if (!saveTimer) {
      if (cfg.ledRing?.mode) {
        document.querySelectorAll("#ringModes .seg").forEach((b) => {
          b.classList.toggle("active", b.dataset.mode === cfg.ledRing.mode);
        });
      }
      if (cfg.audio?.outputRoute) {
        document.querySelectorAll("#audioRoutes .seg").forEach((b) => {
          b.classList.toggle("active", b.dataset.route === cfg.audio.outputRoute);
        });
      }
      if (cfg.audio?.eqPreset && $("eqPreset") && document.activeElement !== $("eqPreset")) {
        $("eqPreset").value = cfg.audio.eqPreset;
      }
      const wTrim = cfg.audio?.wooferTrim ?? 0;
      if (Date.now() > ampVolPendingUntil && $("wooferVol") && document.activeElement !== $("wooferVol")) {
        $("wooferVol").value = wTrim;
        if ($("lblWooferVol")) $("lblWooferVol").textContent = formatTrimLabel(wTrim);
      }
      const tTrim = cfg.audio?.tweetersTrim ?? 0;
      if (Date.now() > ampVolPendingUntil && $("tweetersVol") && document.activeElement !== $("tweetersVol")) {
        $("tweetersVol").value = tTrim;
        if ($("lblTweetersVol")) $("lblTweetersVol").textContent = formatTrimLabel(tTrim);
      }
      const hTrim = cfg.audio?.headphoneTrim ?? 0;
      if (Date.now() > ampVolPendingUntil && $("hpVol") && document.activeElement !== $("hpVol")) {
        $("hpVol").value = hTrim;
        if ($("lblHpVol")) $("lblHpVol").textContent = formatTrimLabel(hTrim);
      }
    }
  }

  // Bind ring mode & route buttons
  document.querySelectorAll("#ringModes .seg").forEach((b) => {
    b.addEventListener("click", () => {
      document.querySelectorAll("#ringModes .seg").forEach((x) => x.classList.remove("active"));
      b.classList.add("active");
      scheduleConfigPush();
    });
  });
  document.querySelectorAll("#audioRoutes .seg").forEach((b) => {
    b.addEventListener("click", () => {
      document.querySelectorAll("#audioRoutes .seg").forEach((x) => x.classList.remove("active"));
      b.classList.add("active");
      scheduleConfigPush();
    });
  });

  // Live slider labels + immediate config sync
  [
    ["ringBrightness", "lblBrightness"],
    ["ringMinAuto", "lblMinAuto"],
    ["ringMaxAuto", "lblMaxAuto"],
    ["ringSpeed", "lblSpeed"],
    ["scrBri", "lblScrBri"],
    ["scrSpeed", "lblScrSpeed"]
  ].forEach(([id, lbl]) => {
    const el = $(id);
    if (!el) return;
    el.addEventListener("input", () => {
      if ($(lbl)) $(lbl).textContent = el.value;
      scheduleConfigPush();
    });
  });

  let ampVolSeq = 0;
  function pushAmpVolumePatch(field, value) {
    const seq = ++ampVolSeq;
    ampVolPendingUntil = Date.now() + 800;
    clearTimeout(ampVolTimer);
    ampVolTimer = setTimeout(async () => {
      ampVolTimer = null;
      try {
        const next = await api("/api/config", "POST", { audio: { [field]: value } });
        if (seq === ampVolSeq) ampVolPendingUntil = 0;
        applyTelemetry(next, false);
      } catch (e) {
        if (seq === ampVolSeq) ampVolPendingUntil = 0;
        showToast("Ошибка баланса: " + e.message, true);
      }
    }, 90);
  }
  [
    ["wooferVol", "lblWooferVol", "wooferTrim"],
    ["tweetersVol", "lblTweetersVol", "tweetersTrim"],
    ["hpVol", "lblHpVol", "headphoneTrim"]
  ].forEach(([id, lbl, field]) => {
    const el = $(id);
    if (!el) return;
    el.addEventListener("input", () => {
      const val = parseInt(el.value, 10) || 0;
      if ($(lbl)) $(lbl).textContent = formatTrimLabel(val);
      pushAmpVolumePatch(field, val);
    });
  });

  function pushSystemVolume(pct) {
    const clamped = Math.max(0, Math.min(100, Math.round(pct)));
    if ($("sysVol")) $("sysVol").value = clamped;
    if ($("lblSysVol")) $("lblSysVol").textContent = `${clamped}%`;
    if ($("badgeVol")) $("badgeVol").textContent = `Громкость: ${clamped}%`;
    clearTimeout(volTimer);
    volTimer = setTimeout(async () => {
      volTimer = null;
      try {
        await api("/api/audio/volume", "POST", { percent: clamped });
      } catch (e) {
        showToast("Ошибка громкости: " + e.message, true);
      }
    }, 80);
  }
  if ($("sysVol")) $("sysVol").addEventListener("input", () => pushSystemVolume(parseInt($("sysVol").value, 10) || 0));
  if ($("btnVolDown")) $("btnVolDown").addEventListener("click", () => pushSystemVolume((parseInt($("sysVol")?.value ?? "50", 10) || 0) - 5));
  if ($("btnVolUp")) $("btnVolUp").addEventListener("click", () => pushSystemVolume((parseInt($("sysVol")?.value ?? "50", 10) || 0) + 5));

  function pushEqDebounced() {
    clearTimeout(eqPushTimer);
    eqPushTimer = setTimeout(async () => {
      eqPushTimer = null;
      try {
        await api("/api/audio/eq", "POST", {
          preset: $("eqPreset")?.value || "custom",
          bands: currentEqBands,
          bassBoost: parseInt($("bassBoost")?.value || "20", 10)
        });
      } catch (e) {
        showToast("Ошибка EQ: " + e.message, true);
      }
    }, 120);
  }

  function renderEqCurve() {
    const svg = $("eqCurveSvg");
    if (!svg) return;
    svg.innerHTML = "";

    const zeroLine = document.createElementNS("http://www.w3.org/2000/svg", "line");
    zeroLine.setAttribute("x1", "10");
    zeroLine.setAttribute("y1", "45");
    zeroLine.setAttribute("x2", "310");
    zeroLine.setAttribute("y2", "45");
    zeroLine.setAttribute("stroke", "rgba(255,255,255,0.15)");
    zeroLine.setAttribute("stroke-dasharray", "3 3");
    svg.appendChild(zeroLine);

    const pts = [];
    const n = currentEqBands.length;
    for (let i = 0; i < n; i++) {
      const x = 20 + i * (280 / (n - 1));
      const gain = Math.max(-12, Math.min(12, currentEqBands[i] || 0));
      const y = 45 - (gain / 12) * 36;
      pts.push({ x, y });
    }

    if (pts.length === 0) return;

    let d = `M ${pts[0].x} ${pts[0].y}`;
    for (let i = 0; i < pts.length - 1; i++) {
      const p0 = pts[i];
      const p1 = pts[i + 1];
      const mx = (p0.x + p1.x) / 2;
      d += ` C ${mx} ${p0.y}, ${mx} ${p1.y}, ${p1.x} ${p1.y}`;
    }

    const areaD = `${d} L ${pts[pts.length - 1].x} 90 L ${pts[0].x} 90 Z`;
    const areaPath = document.createElementNS("http://www.w3.org/2000/svg", "path");
    areaPath.setAttribute("d", areaD);
    areaPath.setAttribute("fill", "rgba(0, 212, 255, 0.12)");
    svg.appendChild(areaPath);

    const linePath = document.createElementNS("http://www.w3.org/2000/svg", "path");
    linePath.setAttribute("d", d);
    linePath.setAttribute("fill", "none");
    linePath.setAttribute("stroke", "#00d4ff");
    linePath.setAttribute("stroke-width", "2.2");
    linePath.setAttribute("stroke-linecap", "round");
    svg.appendChild(linePath);

    for (let i = 0; i < pts.length; i++) {
      const dot = document.createElementNS("http://www.w3.org/2000/svg", "circle");
      dot.setAttribute("cx", pts[i].x.toFixed(1));
      dot.setAttribute("cy", pts[i].y.toFixed(1));
      dot.setAttribute("r", "3.5");
      dot.setAttribute("fill", "#38bdf8");
      svg.appendChild(dot);
    }
  }

  function updateEqSliders() {
    for (let i = 0; i < 10; i++) {
      const slider = $(`eqSlider_${i}`);
      const lbl = $(`lblEqVal_${i}`);
      const val = (currentEqBands[i] !== undefined ? currentEqBands[i] : 0).toFixed(1);
      if (slider && document.activeElement !== slider) slider.value = val;
      if (lbl) lbl.textContent = (val > 0 ? `+${val}` : val) + " dB";
    }
  }

  function init10BandEq() {
    const row = $("eqSlidersRow");
    if (!row) return;
    row.innerHTML = "";
    for (let i = 0; i < 10; i++) {
      const col = document.createElement("div");
      col.className = "eq-col";

      const val = (currentEqBands[i] || 0).toFixed(1);
      const valSpan = document.createElement("span");
      valSpan.className = "eq-val";
      valSpan.id = `lblEqVal_${i}`;
      valSpan.textContent = (val > 0 ? `+${val}` : val) + " dB";

      const input = document.createElement("input");
      input.type = "range";
      input.id = `eqSlider_${i}`;
      input.min = "-12";
      input.max = "12";
      input.step = "0.5";
      input.value = val;

      input.addEventListener("input", () => {
        const num = parseFloat(input.value) || 0;
        currentEqBands[i] = num;
        valSpan.textContent = (num > 0 ? `+${num.toFixed(1)}` : num.toFixed(1)) + " dB";
        if ($("eqPreset")) $("eqPreset").value = "custom";
        renderEqCurve();
        pushEqDebounced();
      });

      const lblSpan = document.createElement("span");
      lblSpan.className = "eq-lbl";
      lblSpan.textContent = ISO_LABELS[i];

      col.appendChild(valSpan);
      col.appendChild(input);
      col.appendChild(lblSpan);
      row.appendChild(col);
    }
    renderEqCurve();

    if ($("eqPreset")) {
      $("eqPreset").addEventListener("change", () => {
        const p = $("eqPreset").value;
        if (EQ_PRESETS[p]) {
          currentEqBands = EQ_PRESETS[p].slice();
          updateEqSliders();
          renderEqCurve();
          pushEqDebounced();
        }
      });
    }

    if ($("bassBoost")) {
      $("bassBoost").addEventListener("input", () => {
        const b = parseInt($("bassBoost").value, 10) || 0;
        if ($("lblBassBoost")) $("lblBassBoost").textContent = `${b}%`;
        pushEqDebounced();
      });
    }

    if ($("btnResetEq")) {
      $("btnResetEq").addEventListener("click", () => {
        currentEqBands = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
        if ($("eqPreset")) $("eqPreset").value = "flat";
        updateEqSliders();
        renderEqCurve();
        pushEqDebounced();
        showToast("Эквалайзер сброшен в 0 дБ");
      });
    }
  }

  function renderMemoryNotes() {
    const box = $("aliceMemoryBox");
    if (!box) return;
    box.innerHTML = "";
    if (!currentMemoryNotes || currentMemoryNotes.length === 0) {
      box.innerHTML = '<div class="hint">Заметок пока нет. Добавьте ниже или скажите Алисе голосом «Запомни...»</div>';
      return;
    }
    currentMemoryNotes.forEach((note, idx) => {
      const item = document.createElement("div");
      item.className = "memory-note-item";
      const text = document.createElement("span");
      text.className = "memory-note-text";
      text.textContent = note;
      const btnDel = document.createElement("button");
      btnDel.className = "memory-note-del";
      btnDel.innerHTML = "✕";
      btnDel.title = "Удалить";
      btnDel.addEventListener("click", () => {
        currentMemoryNotes.splice(idx, 1);
        renderMemoryNotes();
        scheduleConfigPush();
      });
      item.appendChild(text);
      item.appendChild(btnDel);
      box.appendChild(item);
    });
  }

  function initAliceMemoryAndLocation() {
    if ($("btnAddMemoryNote")) {
      $("btnAddMemoryNote").addEventListener("click", () => {
        const inp = $("newMemoryNote");
        if (!inp) return;
        const val = inp.value.trim();
        if (!val) return;
        currentMemoryNotes.push(val);
        inp.value = "";
        renderMemoryNotes();
        scheduleConfigPush();
        showToast("Факт добавлен в память Алисы!");
      });
    }

    if ($("btnDetectLocation")) {
      $("btnDetectLocation").addEventListener("click", async () => {
        try {
          showToast("Определение местоположения по IP...");
          const res = await api("/api/location/detect", "POST", {});
          if ($("locCity")) {
            $("locCity").value = "";
            $("locCity").placeholder = res.city ? `Автоматически: ${res.city}` : "Автоопределение по IP...";
          }
          if (res.city) {
            showToast(`Определено: ${res.city} (${res.country})`);
          } else {
            showToast("Город не удалось определить", true);
          }
        } catch (e) {
          showToast("Ошибка геолокации: " + e.message, true);
        }
      });
    }

    if ($("btnTestWeather")) {
      $("btnTestWeather").addEventListener("click", async () => {
        try {
          const res = await api("/api/weather");
          const el = $("weatherTestResult");
          if (res && res.ok) {
            const cur = res.current || {};
            const desc = `🌡 ${cur.tempC ?? cur.tempNum ?? "?"}°C, ${cur.condition || "Ясно"} (${res.city || ""})`;
            if (el) el.textContent = desc;
            showToast(desc);
          } else {
            if (el) el.textContent = "Погода недоступна";
            showToast("Погода недоступна", true);
          }
        } catch (e) {
          showToast("Ошибка погоды: " + e.message, true);
        }
      });
    }

    if ($("micSensitivity")) {
      $("micSensitivity").addEventListener("input", () => {
        const val = parseInt($("micSensitivity").value, 10) || 85;
        if ($("lblMicSens")) $("lblMicSens").textContent = `${val}%`;
        scheduleConfigPush();
      });
    }
  }

  [
    "ringColor", "ringSecColor", "ringAutoBrightness",
    "scrMode", "scrText", "scrColor", "scrAutoBri", "clockStyle", "visualizerStyle", "autoMusicVisualizer",
    "eqPreset", "actShort", "actLong", "remoteShort", "remoteLong", "remoteDouble",
    "remoteLeftLong", "remoteRightLong", "remoteBackLong", "remotePlayDouble",
    "rotaryInvert", "rotaryStep", "locCity",
    "wyoEn", "wyoAlways", "astEnabled", "astMode", "astVoice", "astModel",
    "astPrompt", "astWakeEn", "astChime", "astQuickCommands", "astSearch", "astEchoGuard", "astQuietWhisper",
    "astFollowUp", "astMaxSess", "otaAutoCheck", "otaSyncBootanim", "otaRepo", "otaBranch"
  ].forEach((id) => {
    if ($(id)) $(id).addEventListener("change", scheduleConfigPush);
  });

  init10BandEq();
  initAliceMemoryAndLocation();

  if ($("btnSaveAll")) $("btnSaveAll").addEventListener("click", () => pushConfig(true));
  if ($("btnSaveAlice")) $("btnSaveAlice").addEventListener("click", () => pushConfig(true));
  if ($("astAgentMode")) {
    $("astAgentMode").addEventListener("change", async () => {
      const enabled = $("astAgentMode").checked;
      try {
        const res = await api("/api/assistant/agent_mode", "POST", { enabled });
        if (state && state.config && state.config.assistant) state.config.assistant.agentMode = !!res.agentMode;
        if (state && state.assistant) state.assistant.agentMode = !!res.agentMode;
        applyAliceBanner(state?.assistant?.state || "idle", state?.assistant, state?.config?.assistant);
        showToast(res.agentMode
          ? "Режим Агента включён — Алиса может выполнять ADB и системные команды"
          : "Режим Агента выключен");
      } catch (e) {
        $("astAgentMode").checked = !enabled;
        showToast("Не удалось переключить Режим Агента: " + (e?.message || e), true);
      }
    });
  }
  if ($("btnSaveSmartHome")) $("btnSaveSmartHome").addEventListener("click", () => pushConfig(true));

  if ($("btnToggleKeyVis")) {
    $("btnToggleKeyVis").addEventListener("click", () => {
      const inp = $("astApiKey");
      if (!inp) return;
      const show = inp.type === "password";
      inp.type = show ? "text" : "password";
      $("btnToggleKeyVis").textContent = show ? "Скрыть" : "Показать";
    });
  }

  async function triggerAlice() {
    try {
      await api("/api/assistant/trigger", "POST", { reason: "webui" });
      showToast("Алиса слушает...");
    } catch {
      showToast("Не удалось запустить Алису (проверьте ключ)", true);
    }
  }
  async function stopAlice() {
    try {
      await api("/api/assistant/stop", "POST", {});
      showToast("Диалог остановлен");
    } catch (e) {
      showToast("Ошибка: " + e.message, true);
    }
  }
  async function sendAliceText() {
    const inp = $("aliceTextPrompt");
    if (!inp) return;
    const val = inp.value.trim();
    if (!val) return;
    inp.value = "";
    try {
      await api("/api/assistant/text", "POST", { text: val });
    } catch (e) {
      showToast("Ошибка отправки: " + e.message, true);
    }
  }

  if ($("btnQuickAlice")) {
    $("btnQuickAlice").addEventListener("click", () => {
      if (state?.assistant?.active) stopAlice();
      else triggerAlice();
    });
  }
  if ($("btnAliceTrigger")) $("btnAliceTrigger").addEventListener("click", triggerAlice);
  if ($("btnAliceStop")) $("btnAliceStop").addEventListener("click", stopAlice);
  if ($("btnAliceSendText")) $("btnAliceSendText").addEventListener("click", sendAliceText);
  if ($("aliceTextPrompt")) {
    $("aliceTextPrompt").addEventListener("keydown", (e) => {
      if (e.key === "Enter") sendAliceText();
    });
  }

  async function sendMusicCmd(payload, toastOk) {
    try {
      const result = await api("/api/music/command", "POST", payload);
      if (result.ok === false) throw new Error("Не удалось передать команду приложению");
      if (toastOk) showToast(toastOk);
    } catch (e) {
      showToast("Ошибка Яндекс Музыки: " + e.message, true);
    }
  }
  if ($("btnQuickMusic")) {
    $("btnQuickMusic").addEventListener("click", () => {
      const m = state?.music || {};
      if (m.playing || m.paused) {
        sendMusicCmd({ cmd: "music_action", action: "PLAY_PAUSE" });
      } else {
        sendMusicCmd({ cmd: "play_my_wave", mood: "any", openUi: false }, "Запускаем Мою волну...");
      }
    });
  }
  if ($("btnLaunchMusic")) $("btnLaunchMusic").addEventListener("click", () => sendMusicCmd({ cmd: "launch_music" }, "Открываем Яндекс Музыку на ТВ"));
  if ($("btnMusicWave")) $("btnMusicWave").addEventListener("click", () => sendMusicCmd({ cmd: "play_my_wave", mood: "any", openUi: false }, "Запускаем Мою волну..."));
  [
    ["btnWaveEnergetic", "energetic", "Бодрая"],
    ["btnWaveJoy", "fun", "Весёлая"],
    ["btnWaveCalm", "calm", "Спокойная"],
    ["btnWaveSad", "sad", "Грустная"]
  ].forEach(([id, mood, label]) => {
    if ($(id)) $(id).addEventListener("click", () => sendMusicCmd({ cmd: "play_my_wave", mood, openUi: false }, `Запускаем волну: ${label}...`));
  });
  if ($("btnMusicPrev")) $("btnMusicPrev").addEventListener("click", () => sendMusicCmd({ cmd: "music_action", action: "PREV" }));
  if ($("btnMusicPlayPause")) $("btnMusicPlayPause").addEventListener("click", () => sendMusicCmd({ cmd: "music_action", action: "PLAY_PAUSE" }));
  if ($("btnMusicNext")) $("btnMusicNext").addEventListener("click", () => sendMusicCmd({ cmd: "music_action", action: "NEXT" }));
  if ($("btnMusicLike")) $("btnMusicLike").addEventListener("click", () => sendMusicCmd({ cmd: "music_action", action: "LIKE" }));
  if ($("btnMusicLikedList")) $("btnMusicLikedList").addEventListener("click", () => sendMusicCmd({ cmd: "music_action", action: "PLAY_LIKED" }, "Включаем «Мне нравится»..."));

  function searchAndPlayMusic() {
    const inp = $("musicSearchInput");
    if (!inp) return;
    const q = inp.value.trim();
    if (!q) return;
    inp.value = "";
    sendMusicCmd({ cmd: "play_query", query: q, openUi: false }, `Включаем: ${q}`);
  }
  if ($("btnMusicSearch")) $("btnMusicSearch").addEventListener("click", searchAndPlayMusic);
  if ($("musicSearchInput")) {
    $("musicSearchInput").addEventListener("keydown", (e) => {
      if (e.key === "Enter") searchAndPlayMusic();
    });
  }
  if ($("btnPushMusicOAuth")) {
    $("btnPushMusicOAuth").addEventListener("click", async () => {
      const inp = $("musicOAuthInput");
      const tok = inp ? inp.value.trim() : "";
      if (!tok) return;
      await sendMusicCmd({ cmd: "music_oauth", token: tok, autoPlay: true }, "Токен передан плееру");
      if (inp) inp.value = "";
    });
  }

  if ($("btnQuickMute")) {
    $("btnQuickMute").addEventListener("click", async () => {
      if (ws && ws.readyState === 1) {
        ws.send(JSON.stringify({ cmd: "mute_toggle" }));
      } else {
        try {
          const nextMute = !(state?.config?.audio?.micMuted);
          const next = await api("/api/config", "POST", { audio: { micMuted: nextMute } });
          applyTelemetry(next, false);
        } catch (e) {
          showToast("Ошибка: " + e.message, true);
        }
      }
    });
  }

  if ($("btnQuickBt")) {
    $("btnQuickBt").addEventListener("click", async () => {
      try {
        const res = await api("/api/bluetooth/toggle", "POST");
        if (res.enabled) {
          showToast(res.discoverable ? "Режим Bluetooth (A2DP) включён, колонка видна для подключения" : "Bluetooth включён");
        } else {
          showToast("Bluetooth выключен");
        }
      } catch (e) {
        showToast("Ошибка Bluetooth: " + e.message, true);
      }
    });
  }

  if ($("btnResetAudioTrim")) {
    $("btnResetAudioTrim").addEventListener("click", async () => {
      try {
        clearTimeout(ampVolTimer);
        ampVolTimer = null;
        ampVolSeq++;
        ampVolPendingUntil = 0;
        const next = await api("/api/config", "POST", {
          audio: { wooferTrim: 0, tweetersTrim: 0, headphoneTrim: 0 }
        });
        applyTelemetry(next, false);
        showToast("Баланс громкости сброшен (0.0 дБ)");
      } catch (e) {
        showToast("Ошибка сброса баланса: " + e.message, true);
      }
    });
  }

  // Speaker diagnostic tones
  async function playTone(freq, duration, sweep, label) {
    const stat = $("toneStatusText");
    if (stat) stat.textContent = `Воспроизведение: ${label}...`;
    try {
      await api("/api/audio/test_tone", "POST", { freq, duration, sweep: !!sweep });
      showToast(`Тест: ${label}`);
      if (stat) setTimeout(() => { if (stat.textContent && stat.textContent.includes(label)) stat.textContent = ""; }, duration + 500);
    } catch (e) {
      showToast("Ошибка теста тона: " + e.message, true);
      if (stat) stat.textContent = "Ошибка воспроизведения";
    }
  }

  if ($("btnTestSub")) $("btnTestSub").addEventListener("click", () => playTone(60, 900, false, "Сабвуфер 60 Гц"));
  if ($("btnTestSpeech")) $("btnTestSpeech").addEventListener("click", () => playTone(1000, 700, false, "Голосовой СЧ 1000 Гц"));
  if ($("btnTestHigh")) $("btnTestHigh").addEventListener("click", () => playTone(8000, 700, false, "ВЧ Твитеры 8000 Гц"));
  if ($("btnTestSweep")) $("btnTestSweep").addEventListener("click", () => playTone(1000, 1600, true, "Свип-тон 20-15k Гц"));
  if ($("btnTestChime")) {
    $("btnTestChime").addEventListener("click", async () => {
      try {
        await api("/api/assistant/trigger", "POST", { reason: "test_chime" });
        showToast("Воспроизведён звуковой гонг Алисы");
      } catch (e) {
        showToast("Ошибка: " + e.message, true);
      }
    });
  }

  // LED Screen text & pixel test
  function hexToRgb(hex) {
    let c = (hex || "#ffffff").replace("#", "");
    if (c.length === 3) c = c[0] + c[0] + c[1] + c[1] + c[2] + c[2];
    const num = parseInt(c, 16) || 0xffffff;
    return { r: (num >> 16) & 255, g: (num >> 8) & 255, b: num & 255 };
  }

  if ($("btnFlashScreenText")) {
    $("btnFlashScreenText").addEventListener("click", async () => {
      const txt = $("scrText")?.value.trim() || "OPEN STATION";
      const rgb = hexToRgb($("scrColor")?.value || "#ffffff");
      try {
        await api("/api/screen/text", "POST", { text: txt, durationMs: 4500, r: rgb.r, g: rgb.g, b: rgb.b });
        showToast(`Текст «${txt}» отправлен на экран станции`);
      } catch (e) {
        showToast("Ошибка экрана: " + e.message, true);
      }
    });
  }

  if ($("btnScreenPixelTest")) {
    $("btnScreenPixelTest").addEventListener("click", async () => {
      try {
        await api("/api/screen/text", "POST", { text: "█████████████████████████", durationMs: 3500, r: 255, g: 255, b: 255 });
        showToast("Тест матричных светодиодов запущен (3.5 сек)");
      } catch (e) {
        showToast("Ошибка теста: " + e.message, true);
      }
    });
  }

  // Bootanimation actions
  async function setBootanimBusy(busy) {
    bootanimBusy = busy;
    applyBootanimState(state?.bootanim || {});
  }

  if ($("btnBootanimOta")) {
    $("btnBootanimOta").addEventListener("click", async () => {
      setBootanimBusy(true);
      try {
        const r = await api("/api/bootanim/install_ota", "POST", {});
        if (state) {
          state.bootanim = r.bootanim || state.bootanim;
          if (r.ota) state.ota = r.ota;
        }
        applyBootanimState(r.bootanim || {});
        if (r.ota) applyOtaState(r.ota, state?.firmwareVersion);
        showToast(r.message || "Фирменная заставка установлена!");
      } catch (e) {
        showToast("Ошибка: " + e.message, true);
      } finally {
        setBootanimBusy(false);
      }
    });
  }

  if ($("btnBootanimUpload") && $("bootanimFileInput")) {
    $("btnBootanimUpload").addEventListener("click", () => {
      $("bootanimFileInput").value = "";
      $("bootanimFileInput").click();
    });
    $("bootanimFileInput").addEventListener("change", async () => {
      const file = $("bootanimFileInput").files && $("bootanimFileInput").files[0];
      if (!file) return;
      if (file.size > 24 * 1024 * 1024) {
        showToast("Файл слишком большой (максимум 24 МБ)", true);
        return;
      }
      setBootanimBusy(true);
      showToast("Загрузка заставки на станцию...");
      try {
        const headers = { "Content-Type": "application/zip" };
        const res = await fetch("/api/bootanim/upload", {
          method: "POST",
          headers,
          body: file
        });
        let data = null;
        try { data = await res.json(); } catch {}
        if (!res.ok || (data && data.ok === false)) {
          throw new Error((data && (data.message || data.error)) || `HTTP ${res.status}`);
        }
        if (state && data?.bootanim) state.bootanim = data.bootanim;
        applyBootanimState(data?.bootanim || {});
        showToast(data?.message || "Своя заставка установлена!");
      } catch (e) {
        showToast("Не удалось установить заставку: " + e.message, true);
      } finally {
        setBootanimBusy(false);
      }
    });
  }

  if ($("btnBootanimRestore")) {
    $("btnBootanimRestore").addEventListener("click", async () => {
      setBootanimBusy(true);
      try {
        const r = await api("/api/bootanim/restore", "POST", {});
        if (state && r.bootanim) state.bootanim = r.bootanim;
        applyBootanimState(r.bootanim || {});
        showToast(r.message || "Оригинальная заставка восстановлена");
      } catch (e) {
        showToast("Ошибка: " + e.message, true);
      } finally {
        setBootanimBusy(false);
      }
    });
  }

  if ($("btnBootanimPreview")) {
    $("btnBootanimPreview").addEventListener("click", async () => {
      try {
        const r = await api("/api/bootanim/preview", "POST", {});
        showToast(r.message || "Предпросмотр запущен на экране ТВ");
      } catch (e) {
        showToast("Ошибка предпросмотра: " + e.message, true);
      }
    });
  }

  // Remote D-pad & CEC & Text
  const CEC_LABELS = {
    wakeup: "Включение ТВ",
    standby: "Команда выключения отправлена телевизору",
    volume_up: "ТВ громче",
    volume_down: "ТВ тише"
  };
  document.querySelectorAll("[data-key]").forEach((btn) => {
    btn.addEventListener("click", () => {
      if (ws && ws.readyState === 1) ws.send(JSON.stringify({ cmd: "key", action: btn.dataset.key }));
      else api("/api/remote/key", "POST", { action: btn.dataset.key }).catch(() => {});
    });
  });
  document.querySelectorAll("[data-cec]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      try {
        await api("/api/remote/cec", "POST", { cmd: btn.dataset.cec });
        showToast(CEC_LABELS[btn.dataset.cec] || "Команда отправлена ТВ");
      } catch (e) { showToast("Ошибка управления ТВ: " + e.message, true); }
    });
  });
  if ($("btnSendRemoteText")) {
    $("btnSendRemoteText").addEventListener("click", async () => {
      const inp = $("remoteTextInput");
      const val = inp ? inp.value : "";
      if (!val) return;
      try {
        await api("/api/remote/text", "POST", { text: val });
        if (inp) inp.value = "";
        showToast("Текст отправлен на ТВ");
      } catch (e) { showToast("Ошибка отправки текста: " + e.message, true); }
    });
  }
  if ($("remoteTextInput")) {
    $("remoteTextInput").addEventListener("keydown", (e) => {
      if (e.key === "Enter" && $("btnSendRemoteText")) $("btnSendRemoteText").click();
    });
  }

  async function loadApps() {
    const grid = $("appsGrid");
    if (!grid) return;
    try {
      const d = await api("/api/apps");
      grid.innerHTML = "";
      const list = d.apps || [];
      if (!list.length) {
        grid.textContent = "Нет приложений";
        return;
      }
      list.forEach((a) => {
        const tile = document.createElement("button");
        tile.className = "app-tile";
        const nm = document.createElement("div");
        nm.className = "name";
        nm.textContent = a.label || a.package;
        tile.appendChild(nm);
        tile.addEventListener("click", () => {
          api("/api/apps/launch", "POST", { package: a.package })
            .then(() => showToast("Запуск: " + (a.label || a.package)))
            .catch(() => {});
        });
        grid.appendChild(tile);
      });
    } catch {
      grid.textContent = "Не удалось загрузить список приложений";
    }
  }
  if ($("btnRefreshApps")) $("btnRefreshApps").addEventListener("click", loadApps);

  // Touchpad Mode & Mouse Control (via WS / /api/remote/mouse)
  function setRemoteInputMode(mode) {
    const useTouch = mode === "touchpad";
    document.querySelectorAll("#remoteInputMode .seg").forEach((b) => {
      b.classList.toggle("active", b.dataset.rmode === (useTouch ? "touchpad" : "dpad"));
    });
    if ($("remoteDpadWrap")) $("remoteDpadWrap").style.display = useTouch ? "none" : "block";
    if ($("remoteTouchpadWrap")) $("remoteTouchpadWrap").style.display = useTouch ? "block" : "none";
    try { localStorage.setItem("os_remote_mode", useTouch ? "touchpad" : "dpad"); } catch {}
  }

  document.querySelectorAll("#remoteInputMode .seg").forEach((btn) => {
    btn.addEventListener("click", () => setRemoteInputMode(btn.dataset.rmode));
  });
  try {
    const savedMode = localStorage.getItem("os_remote_mode");
    if (savedMode === "touchpad") setRemoteInputMode("touchpad");
  } catch {}

  function sendMouseMove(dx, dy) {
    if (!dx && !dy) return;
    if (ws && ws.readyState === 1) {
      ws.send(JSON.stringify({ cmd: "mouse_move", dx, dy }));
    } else {
      api("/api/remote/mouse", "POST", { dx, dy }).catch(() => {});
    }
  }

  function sendMouseClick(button = 0, count = 1) {
    if (ws && ws.readyState === 1) {
      ws.send(JSON.stringify({ cmd: "mouse_click", button, count }));
    } else {
      api("/api/remote/mouse", "POST", { click: true, button, count }).catch(() => {});
    }
  }

  function sendMouseScroll(dy) {
    if (!dy) return;
    if (ws && ws.readyState === 1) {
      ws.send(JSON.stringify({ cmd: "mouse_scroll", dy }));
    } else {
      api("/api/remote/mouse", "POST", { scroll: dy }).catch(() => {});
    }
  }

  if ($("touchSensitivity")) {
    $("touchSensitivity").addEventListener("input", () => {
      if ($("lblTouchSens")) $("lblTouchSens").textContent = parseFloat($("touchSensitivity").value).toFixed(1) + "×";
    });
  }
  if ($("btnMouseLeft")) $("btnMouseLeft").addEventListener("click", () => sendMouseClick(0, 1));
  if ($("btnMouseDouble")) $("btnMouseDouble").addEventListener("click", () => sendMouseClick(0, 2));
  if ($("btnMouseRight")) $("btnMouseRight").addEventListener("click", () => sendMouseClick(1, 1));
  if ($("btnScrollUp")) $("btnScrollUp").addEventListener("click", () => sendMouseScroll(2));
  if ($("btnScrollDown")) $("btnScrollDown").addEventListener("click", () => sendMouseScroll(-2));

  if ($("touchpadSurface")) {
    const pad = $("touchpadSurface");
    const badge = $("touchpadStateBadge");
    const pointers = new Map();
    let accDx = 0;
    let accDy = 0;
    let accScroll = 0;
    let rafId = 0;
    let gestureStartMs = 0;
    let maxPointers = 0;
    let totalTravel = 0;

    function flushMouse() {
      rafId = 0;
      const dx = Math.trunc(accDx);
      const dy = Math.trunc(accDy);
      if (dx !== 0 || dy !== 0) {
        accDx -= dx;
        accDy -= dy;
        sendMouseMove(dx, dy);
      }
      const sc = Math.trunc(accScroll);
      if (sc !== 0) {
        accScroll -= sc;
        sendMouseScroll(sc);
      }
    }

    function scheduleFlush() {
      if (!rafId) rafId = requestAnimationFrame(flushMouse);
    }

    pad.addEventListener("pointerdown", (e) => {
      if (e.button === 2) {
        e.preventDefault();
        sendMouseClick(1, 1);
        return;
      }
      e.preventDefault();
      try { pad.setPointerCapture(e.pointerId); } catch {}
      if (pointers.size === 0) {
        gestureStartMs = performance.now();
        maxPointers = 1;
        totalTravel = 0;
      }
      pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
      maxPointers = Math.max(maxPointers, pointers.size);
      pad.classList.add("active");
      if (badge) badge.textContent = pointers.size >= 2 ? "Скролл / ПКМ" : "Курсор";
    });

    pad.addEventListener("pointermove", (e) => {
      const prev = pointers.get(e.pointerId);
      if (!prev) return;
      const rawDx = e.clientX - prev.x;
      const rawDy = e.clientY - prev.y;
      prev.x = e.clientX;
      prev.y = e.clientY;
      totalTravel += Math.hypot(rawDx, rawDy);

      const sens = $("touchSensitivity") ? parseFloat($("touchSensitivity").value) || 1.5 : 1.5;
      if (pointers.size >= 2) {
        accScroll += (-rawDy * sens) / 22;
      } else {
        const gain = sens * 1.45;
        accDx += rawDx * gain;
        accDy += rawDy * gain;
      }
      scheduleFlush();
    });

    function onPointerEnd(e) {
      if (!pointers.has(e.pointerId)) return;
      pointers.delete(e.pointerId);
      try { pad.releasePointerCapture(e.pointerId); } catch {}
      if (pointers.size === 0) {
        pad.classList.remove("active");
        if (badge) badge.textContent = "Готов";
        const elapsed = performance.now() - gestureStartMs;
        if (totalTravel < 8 && elapsed < 260) {
          sendMouseClick(maxPointers >= 2 ? 1 : 0, 1);
        }
        maxPointers = 0;
      }
    }

    pad.addEventListener("pointerup", onPointerEnd);
    pad.addEventListener("pointercancel", onPointerEnd);
    pad.addEventListener("contextmenu", (e) => e.preventDefault());
    pad.addEventListener("wheel", (e) => {
      e.preventDefault();
      sendMouseScroll(e.deltaY < 0 ? 1 : -1);
    }, { passive: false });
  }

  // APK Installer (drag & drop + progress via /api/apps/install)
  let apkInstalling = false;
  function uploadAndInstallApk(file) {
    if (!file) return;
    if (apkInstalling) {
      showToast("Установка APK уже выполняется...", true);
      return;
    }
    if (!/\.apk$/i.test(file.name || "")) {
      showToast("Выберите файл с расширением .apk", true);
      return;
    }
    if (file.size > 160 * 1024 * 1024) {
      showToast("APK слишком большой (максимум 160 МБ)", true);
      return;
    }

    apkInstalling = true;
    const progWrap = $("apkProgressWrap");
    const progFill = $("apkProgressFill");
    const progLbl = $("apkProgressLabel");
    const statusBox = $("apkInstallStatus");
    if (progWrap) progWrap.style.display = "block";
    if (progFill) progFill.style.width = "0%";
    if (progLbl) progLbl.textContent = `Загрузка ${file.name}: 0%`;
    if (statusBox) {
      statusBox.style.display = "block";
      statusBox.className = "status-banner live";
      statusBox.textContent = `Передача ${file.name} на станцию...`;
    }

    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/api/apps/install", true);
    xhr.timeout = 120000;
    xhr.setRequestHeader("Content-Type", "application/vnd.android.package-archive");

    xhr.upload.onprogress = (ev) => {
      if (!ev.lengthComputable) return;
      const pct = Math.min(100, Math.round((ev.loaded / ev.total) * 100));
      if (progFill) progFill.style.width = `${pct}%`;
      if (progLbl) {
        progLbl.textContent = pct >= 100
          ? "Файл передан — выполняется установка на станции..."
          : `Загрузка ${file.name}: ${pct}%`;
      }
    };

    xhr.onload = () => {
      apkInstalling = false;
      let resp = {};
      try { resp = JSON.parse(xhr.responseText || "{}"); } catch {}
      const ok = xhr.status >= 200 && xhr.status < 300 && resp.ok !== false;
      if (progFill) progFill.style.width = ok ? "100%" : "0%";
      if (statusBox) {
        statusBox.className = "status-banner " + (ok ? "ok" : "err");
        statusBox.textContent = ok
          ? `✓ Приложение ${file.name} установлено${resp.output ? ": " + resp.output.trim() : ""}`
          : `Ошибка установки: ${resp.output || resp.error || "HTTP " + xhr.status}`;
      }
      showToast(ok ? `✓ ${file.name} установлено!` : "Не удалось установить APK", !ok);
      if (ok) loadApps();
    };

    xhr.onerror = xhr.ontimeout = () => {
      apkInstalling = false;
      if (statusBox) {
        statusBox.className = "status-banner err";
        statusBox.textContent = "Ошибка передачи APK или превышен таймаут ожидания";
      }
      showToast("Ошибка передачи APK на станцию", true);
    };

    xhr.send(file);
  }

  if ($("btnChooseApk") && $("apkFileInput")) {
    $("btnChooseApk").addEventListener("click", (e) => {
      e.stopPropagation();
      $("apkFileInput").value = "";
      $("apkFileInput").click();
    });
  }
  if ($("apkDropzone") && $("apkFileInput")) {
    $("apkDropzone").addEventListener("click", () => {
      $("apkFileInput").value = "";
      $("apkFileInput").click();
    });
    $("apkDropzone").addEventListener("dragover", (e) => {
      e.preventDefault();
      $("apkDropzone").classList.add("dragover");
    });
    $("apkDropzone").addEventListener("dragleave", () => {
      $("apkDropzone").classList.remove("dragover");
    });
    $("apkDropzone").addEventListener("drop", (e) => {
      e.preventDefault();
      $("apkDropzone").classList.remove("dragover");
      const file = e.dataTransfer?.files?.[0];
      if (file) uploadAndInstallApk(file);
    });
    $("apkFileInput").addEventListener("change", () => {
      const file = $("apkFileInput").files?.[0];
      if (file) uploadAndInstallApk(file);
    });
  }

  // BLE Remote Pair / Find, Matrix Recovery, Startup Show
  async function triggerPairRemote(force = true) {
    try {
      const r = await api("/api/remote/pair", "POST", { force });
      showToast(r.ok !== false ? "Запущен автопоиск штатного BLE-пульта YNDX..." : (r.error || "Не удалось запустить поиск"), r.ok === false);
    } catch (e) { showToast("Ошибка BLE-поиска: " + e.message, true); }
  }
  async function triggerForgetRemote() {
    try {
      const r = await api("/api/remote/forget", "POST", {});
      showToast(r.ok !== false ? "Память пультов очищена — удерживайте «Домой» и «−» на пульте" : (r.error || "Не удалось очистить сопряжение"), r.ok === false);
    } catch (e) { showToast("Ошибка сброса пульта: " + e.message, true); }
  }
  async function triggerFindRemote() {
    try {
      const r = await api("/api/remote/find", "POST", {});
      showToast(r.ok !== false ? "Отправлен звуковой сигнал поиска на BLE-пульт" : (r.error || "Пульт не подключён"), r.ok === false);
    } catch (e) { showToast("Ошибка поиска пульта: " + e.message, true); }
  }
  if ($("btnPairBleRemote")) $("btnPairBleRemote").addEventListener("click", () => triggerPairRemote(true));
  if ($("btnWizPairBle")) $("btnWizPairBle").addEventListener("click", () => triggerPairRemote(true));
  if ($("btnForgetBleRemote")) $("btnForgetBleRemote").addEventListener("click", triggerForgetRemote);
  if ($("btnWizForgetBle")) $("btnWizForgetBle").addEventListener("click", triggerForgetRemote);
  if ($("btnFindBleRemote")) $("btnFindBleRemote").addEventListener("click", triggerFindRemote);
  if ($("btnWizFindBle")) $("btnWizFindBle").addEventListener("click", triggerFindRemote);
  if ($("btnRemoteBtToggle")) $("btnRemoteBtToggle").addEventListener("click", () => $("btnQuickBt")?.click());

  if ($("btnRecoverScreen")) {
    $("btnRecoverScreen").addEventListener("click", async () => {
      try {
        const r = await api("/api/leds/recover", "POST", {});
        showToast(`Экранчик (fb5) переинициализирован (всего восстановлений: ${r.recoveries ?? 1})`);
      } catch (e) { showToast("Ошибка восстановления fb5: " + e.message, true); }
    });
  }
  if ($("btnStartupShow")) {
    $("btnStartupShow").addEventListener("click", async () => {
      try {
        await api("/api/system/startup_show", "POST", {});
        showToast("Запущено стартовое свето-звуковое шоу!");
      } catch (e) { showToast("Ошибка запуска шоу: " + e.message, true); }
    });
  }

  function connectWs() {
    clearTimeout(wsReconnectTimer);
    wsReconnectTimer = null;
    if (ws) {
      ws.onopen = null;
      ws.onclose = null;
      ws.onerror = null;
      ws.onmessage = null;
      try {
        if (ws.readyState === 0 || ws.readyState === 1) ws.close();
      } catch {}
      ws = null;
    }
    const proto = location.protocol === "https:" ? "wss://" : "ws://";
    ws = new WebSocket(proto + location.host + "/ws");
    ws.binaryType = "arraybuffer";
    ws.onerror = () => {
      try { ws?.close(); } catch {}
    };
    ws.onclose = () => {
      setConn(false);
      if (!wsReconnectTimer) {
        wsReconnectTimer = setTimeout(connectWs, 2000);
      }
    };
    ws.onmessage = (ev) => {
      if (typeof ev.data === "string") {
        try {
          const msg = JSON.parse(ev.data);
          if (msg.type === "telemetry") {
            setConn(true);
            applyTelemetry(msg.data, state === null);
          } else if (msg.type === "alarm_fired" || msg.type === "timer_fired") {
            showToast(msg.type === "alarm_fired"
              ? `Будильник ${msg.time || ""} ${msg.label || ""}`
              : `Таймер «${msg.label || ""}» сработал`);
          } else if (msg.type === "ota") {
            if (state) state.ota = msg.data;
            applyOtaState(msg.data, state?.firmwareVersion);
            if (msg.data?.state === "up_to_date" && msg.data?.progressPct === 100 && !msg.data?.updateAvailable && otaPendingRestart) {
              otaPendingRestart = false;
              showToast("Прошивка обновлена до v" + (msg.data.installedVersion || "1.6.3"));
              if (state?.postOtaWizardPending && $("wizardModal")?.style.display !== "flex") {
                openWizard(1);
              }
            }
          } else if (msg.type === "assistant_event") {
            const fakeAst = Object.assign({}, state?.assistant || {}, {
              state: msg.state || "idle",
              active: !["idle", "error"].includes(msg.state || "idle"),
              lastError: msg.lastError || ""
            });
            if (state) state.assistant = fakeAst;
            applyAliceBanner(msg.state || "idle", fakeAst, state?.config?.assistant);
            updateAliceLiveBox(msg.liveUser || "", msg.liveModel || "");
            if (msg.kind === "message" && msg.text) {
              appendAliceHistoryItem(msg.role || "model", msg.text);
            } else if (msg.kind === "tool_call" && msg.text) {
              appendAliceHistoryItem("tool", msg.text);
            } else if (msg.kind === "error" && msg.text) {
              showToast("Алиса: " + msg.text, true);
            }
          }
        } catch {}
      }
    };
  }

  async function runGeminiLiveTest(keyVal, modelVal, proxyVal, targetEl) {
    if (targetEl) {
      targetEl.textContent = "⏳ Проверка связи с Gemini Live...";
      if (targetEl.classList.contains("status-banner")) targetEl.className = "status-banner warn";
    }
    try {
      const r = await api("/api/assistant/test", "POST", {
        geminiApiKey: keyVal || "",
        geminiModel: modelVal || "gemini-3.8-live",
        geminiProxyHost: proxyVal ?? "smart-auto"
      });
      if (r.ok) {
        const msg = `✓ Связь работает (${r.latencyMs} мс)`;
        if (targetEl) {
          targetEl.textContent = msg;
          if (targetEl.classList.contains("status-banner")) targetEl.className = "status-banner ok";
        }
        showToast(msg);
      } else {
        const err = `✗ Ошибка связи${r.code ? " (" + r.code + ")" : ""}: ${r.error || "нет ответа"}`;
        if (targetEl) {
          targetEl.textContent = err;
          if (targetEl.classList.contains("status-banner")) targetEl.className = "status-banner err";
        }
        showToast(err, true);
      }
      return r;
    } catch (e) {
      const msg = "Ошибка проверки: " + e.message;
      if (targetEl) {
        targetEl.textContent = msg;
        if (targetEl.classList.contains("status-banner")) targetEl.className = "status-banner err";
      }
      showToast(msg, true);
      return null;
    }
  }

  const WIZ_PROMPTS = {
    universal: "Ты — девушка, голосовая помощница Алиса на умной колонке Яндекс Станция Макс с открытой прошивкой OpenStation Max (полный root-доступ и живой доступ в интернет). Отвечай по-русски живо, дружелюбно, естественно и кратко (1-3 предложения, если не просят подробнее). Обязательно используй локальные инструменты: web_search, get_weather, fetch_url, set_volume, set_eq_preset, set_audio_route, set_led_ring, manage_timer, tv_hdmi_cec, remote_control, music_control и manage_apps.",
    concise: "Ты — голосовой ассистент Алиса на Станции Макс. Отвечай максимально кратко и по делу на русском языке (1-2 предложения), сразу выполняй команды управления музыкой, громкостью, ТВ, подсветкой и поиском через доступные инструменты.",
    witty: "Ты — весёлая и остроумная Алиса на Станции Макс. Общайся по-русски тепло, живо и иногда с лёгкой шуткой, оставаясь полезной и используя все встроенные инструменты колонки для музыки, погоды, ТВ и умного дома."
  };
  function matchPromptPresetKey(prompt) {
    const p = (prompt || "").trim();
    if (!p) return "";
    for (const [k, v] of Object.entries(WIZ_PROMPTS)) {
      if (p === v) return k;
    }
    if (
      p.startsWith("Ты — девушка, голосовая помощница ") ||
      p.includes("с открытой прошивкой OpenStation Max (полный root-доступ и живой доступ в интернет)")
    ) {
      return "universal";
    }
    if (p.includes("Отвечай максимально кратко и по делу на русском языке (1-2 предложения)")) {
      return "concise";
    }
    if (p.includes("иногда с лёгкой шуткой, оставаясь полезной")) {
      return "witty";
    }
    return "";
  }
  function syncPromptPresetButtons(prompt) {
    const key = matchPromptPresetKey(prompt);
    document.querySelectorAll("#wizPromptPresets .seg, #astPromptPresets .seg").forEach((b) => {
      b.classList.toggle("active", b.dataset.preset === key);
    });
  }

  const WIZ_TOTAL_STEPS = 7;
  const WIZ_PALETTES = [
    [[56, 189, 248], [16, 185, 129]],
    [[34, 211, 238], [59, 130, 246]],
    [[99, 102, 241], [245, 158, 11]],
    [[139, 92, 246], [232, 198, 134]],
    [[236, 72, 153], [59, 130, 246]],
    [[0, 212, 255], [16, 185, 129]],
    [[16, 185, 129], [251, 191, 36]]
  ];
  const wizWaveBars = new Float32Array(22);
  let wizAuroraMix = [56, 189, 248, 16, 185, 129];

  function hexToRgb(hex) {
    const m = /^#?([0-9a-f]{6})$/i.exec((hex || "").trim());
    if (!m) return [0, 212, 255];
    const v = parseInt(m[1], 16);
    return [(v >> 16) & 255, (v >> 8) & 255, v & 255];
  }

  function spawnWizConfetti() {
    const cols = ["#e8c686", "#38bdf8", "#10b981", "#ec4899", "#a78bfa", "#ffffff"];
    wizConfetti = Array.from({ length: 44 }, () => ({
      x: 0.15 + Math.random() * 0.70,
      y: 0.20 + Math.random() * 0.25,
      vx: (Math.random() - 0.5) * 0.012,
      vy: -0.008 - Math.random() * 0.010,
      size: 3 + Math.random() * 4,
      rot: Math.random() * Math.PI * 2,
      vr: (Math.random() - 0.5) * 0.18,
      color: cols[(Math.random() * cols.length) | 0],
      alpha: 1
    }));
  }

  function ensureCanvasSize(canvas) {
    if (!canvas) return null;
    const rect = canvas.getBoundingClientRect();
    if (!rect.width || !rect.height) return null;
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const w = Math.max(1, Math.round(rect.width * dpr));
    const h = Math.max(1, Math.round(rect.height * dpr));
    if (canvas.width !== w || canvas.height !== h) {
      canvas.width = w;
      canvas.height = h;
    }
    return { w, h, dpr };
  }

  function drawWizAurora(t) {
    const c = $("wizAuroraCanvas");
    const dim = ensureCanvasSize(c);
    if (!dim) return;
    const ctx = c.getContext("2d");
    const { w, h } = dim;
    ctx.clearRect(0, 0, w, h);

    const tgt = WIZ_PALETTES[Math.max(0, Math.min(WIZ_TOTAL_STEPS - 1, wizStep - 1))];
    const tr = [tgt[0][0], tgt[0][1], tgt[0][2], tgt[1][0], tgt[1][1], tgt[1][2]];
    for (let i = 0; i < 6; i++) wizAuroraMix[i] += (tr[i] - wizAuroraMix[i]) * 0.08;

    const c1 = `rgba(${wizAuroraMix[0] | 0},${wizAuroraMix[1] | 0},${wizAuroraMix[2] | 0},0.22)`;
    const c2 = `rgba(${wizAuroraMix[3] | 0},${wizAuroraMix[4] | 0},${wizAuroraMix[5] | 0},0.18)`;

    const x1 = w * (0.28 + Math.sin(t * 0.45) * 0.16);
    const y1 = h * (0.22 + Math.cos(t * 0.35) * 0.12);
    const g1 = ctx.createRadialGradient(x1, y1, 0, x1, y1, Math.max(w, h) * 0.55);
    g1.addColorStop(0, c1);
    g1.addColorStop(1, "rgba(0,0,0,0)");
    ctx.fillStyle = g1;
    ctx.fillRect(0, 0, w, h);

    const x2 = w * (0.76 + Math.cos(t * 0.38) * 0.14);
    const y2 = h * (0.78 + Math.sin(t * 0.42) * 0.14);
    const g2 = ctx.createRadialGradient(x2, y2, 0, x2, y2, Math.max(w, h) * 0.52);
    g2.addColorStop(0, c2);
    g2.addColorStop(1, "rgba(0,0,0,0)");
    ctx.fillStyle = g2;
    ctx.fillRect(0, 0, w, h);

    if (wizConfetti.length) {
      for (const p of wizConfetti) {
        p.x += p.vx;
        p.y += p.vy;
        p.vy += 0.00035;
        p.rot += p.vr;
        p.alpha -= 0.007;
        if (p.alpha <= 0) continue;
        ctx.save();
        ctx.translate(p.x * w, p.y * h);
        ctx.rotate(p.rot);
        ctx.globalAlpha = Math.max(0, p.alpha);
        ctx.fillStyle = p.color;
        ctx.fillRect(-p.size * 0.6, -p.size * 0.35, p.size * 1.2, p.size * 0.7);
        ctx.restore();
      }
      wizConfetti = wizConfetti.filter((p) => p.alpha > 0);
    }
  }

  function drawWizOrb(t) {
    const c = $("wizOrbCanvas");
    if (!c) return;
    const ctx = c.getContext("2d");
    const w = c.width, h = c.height, cx = w * 0.5, cy = h * 0.5;
    ctx.clearRect(0, 0, w, h);

    const astState = state?.assistant?.state || "idle";
    const rms = Number(state?.audio?.engine?.monoRms || 0);
    const amp = Math.min(1, rms / 1800);
    const R = w * (0.44 + amp * 0.03);

    // 1. Glossy 3D obsidian carbon sphere body
    const bodyGrad = ctx.createRadialGradient(
      cx - R * 0.34, cy - R * 0.36, R * 0.04,
      cx, cy, R
    );
    bodyGrad.addColorStop(0, "rgba(46, 48, 56, 0.98)");
    bodyGrad.addColorStop(0.45, "rgba(12, 13, 17, 0.99)");
    bodyGrad.addColorStop(1, "rgba(2, 2, 4, 1)");
    ctx.fillStyle = bodyGrad;
    ctx.beginPath();
    ctx.arc(cx, cy, R, 0, Math.PI * 2);
    ctx.fill();

    // Subtle metallic studio rim
    ctx.strokeStyle = astState === "listening" ? "rgba(186, 230, 253, 0.55)"
      : astState === "speaking" ? "rgba(224, 231, 255, 0.55)"
      : astState === "thinking" ? "rgba(253, 230, 138, 0.50)"
      : "rgba(255, 255, 255, 0.16)";
    ctx.lineWidth = Math.max(1, w * 0.015);
    ctx.beginPath();
    ctx.arc(cx, cy, R - 0.5, 0, Math.PI * 2);
    ctx.stroke();

    // 2. Curved 3D Spherical Halftone Dot Matrix + sweeping specular lobes
    const N = 6;
    const thetaMax = 1.02;
    const invSinMax = 1 / Math.sin(thetaMax);
    const maxEnv = 0.87;
    const tW = t * (astState === "thinking" ? 2.6 : astState === "idle" ? 0.95 : 1.7);
    const l1x = Math.cos(tW) * 0.48, l1y = Math.sin(tW * 0.72 - 0.35) * 0.48 - 0.10;
    const l2x = Math.sin(tW * 0.8 + 1.85) * 0.54, l2y = Math.cos(tW + 0.9) * 0.44 + 0.06;
    const breath = 0.58 + 0.42 * Math.sin(tW + 0.25);
    const voiceBoost = astState !== "idle" ? (0.35 + amp * 0.65) : 0;

    for (let j = -N; j <= N; j++) {
      for (let i = -N; i <= N; i++) {
        const gx = i / N, gy = j / N;
        const r = Math.hypot(gx, gy);
        if (r > 1.03) continue;
        const angR = r * thetaMax;
        const sx = r > 1e-4 ? (gx / r) * Math.sin(angR) : 0;
        const sy = r > 1e-4 ? (gy / r) * Math.sin(angR) : 0;
        const sz = Math.cos(angR);
        const nx = sx * invSinMax * maxEnv;
        const ny = sy * invSinMax * maxEnv;
        const r2d = Math.hypot(nx, ny);
        if (r2d > maxEnv) continue;

        const d1 = (sx - l1x) ** 2 + (sy - l1y) ** 2;
        const d2 = (sx - l2x) ** 2 + (sy - l2y) ** 2;
        const caustic = (1.12 * Math.exp(-d1 * 7) + 0.95 * Math.exp(-d2 * 6.5)) * (0.32 + 0.68 * breath)
          + voiceBoost * Math.exp(-r2d * r2d * 4.0);
        const rimFade = Math.pow(Math.max(0, Math.min(1, (maxEnv - r2d) / 0.18)), 0.75);
        const inten = Math.max(0, Math.min(1, (0.14 + caustic) * rimFade));

        const px = cx + nx * R;
        const py = cy + ny * R;
        const dr = R * 0.056 * (0.48 + 0.52 * sz) * (0.46 + 0.54 * Math.pow(inten, 0.65));
        const lum = (24 + 231 * Math.pow(inten, 0.88)) | 0;
        const alpha = (0.38 + 0.62 * Math.pow(inten, 0.55) * rimFade).toFixed(2);
        ctx.fillStyle = astState === "listening" && inten > 0.45
          ? `rgba(${(lum * 0.82) | 0},${(lum * 0.95) | 0},${lum},${alpha})`
          : `rgba(${lum},${lum},${lum},${alpha})`;
        ctx.beginPath();
        ctx.arc(px, py, Math.max(0.7, dr), 0, Math.PI * 2);
        ctx.fill();
      }
    }
  }

  function drawWizMicWave(t) {
    if (wizStep !== 4) return;
    const c = $("wizMicWaveCanvas");
    const dim = ensureCanvasSize(c);
    if (!dim) return;
    const ctx = c.getContext("2d");
    const { w, h } = dim;
    ctx.clearRect(0, 0, w, h);

    const eng = state?.audio?.engine || {};
    const ch = Array.isArray(eng.channelRms) ? eng.channelRms : [];
    const monoNorm = Math.min(1, Number(eng.monoRms || 0) / 1800);
    const n = wizWaveBars.length;
    const stepX = w / n;
    const barW = Math.max(3, stepX * 0.56);

    for (let i = 0; i < n; i++) {
      const chVal = ch.length ? Math.min(1, Number(ch[i % ch.length] || 0) / 2000) : monoNorm;
      const env = Math.sin((i / (n - 1)) * Math.PI);
      const idle = 0.08 + 0.08 * Math.abs(Math.sin(t * 2.4 + i * 0.45));
      const target = Math.max(idle, (monoNorm * 0.55 + chVal * 0.45) * (0.55 + 0.45 * env));
      wizWaveBars[i] += (target - wizWaveBars[i]) * 0.25;

      const bh = Math.max(3, wizWaveBars[i] * (h - 6));
      const bx = i * stepX + (stepX - barW) * 0.5;
      const by = (h - bh) * 0.5;
      const grad = ctx.createLinearGradient(0, by, 0, by + bh);
      grad.addColorStop(0, "#e8c686");
      grad.addColorStop(1, "#38bdf8");
      ctx.fillStyle = grad;
      if (ctx.roundRect) {
        ctx.beginPath();
        ctx.roundRect(bx, by, barW, bh, barW * 0.5);
        ctx.fill();
      } else {
        ctx.fillRect(bx, by, barW, bh);
      }
    }
  }

  function drawWizLedRing(t) {
    if (wizStep !== 6) return;
    const c = $("wizLedRingCanvas");
    const dim = ensureCanvasSize(c);
    if (!dim) return;
    const ctx = c.getContext("2d");
    const { w, h } = dim;
    ctx.clearRect(0, 0, w, h);

    const cx = w / 2, cy = h / 2;
    const ringR = Math.min(w, h) * 0.36;
    const mode = $("wizRingMode")?.value || state?.config?.ledRing?.mode || "music";
    const rgb = hexToRgb($("wizRingColor")?.value || state?.config?.ledRing?.color || "#00d4ff");
    const px = Array.isArray(state?.hardware?.ringPixels) ? state.hardware.ringPixels : [];
    const hasHw = px.length === 24 && px.some((p) => (p[0] + p[1] + p[2]) > 18);

    ctx.strokeStyle = "rgba(255,255,255,0.12)";
    ctx.lineWidth = Math.max(1.5, w * 0.02);
    ctx.beginPath();
    ctx.arc(cx, cy, ringR, 0, Math.PI * 2);
    ctx.stroke();

    for (let i = 0; i < 24; i++) {
      const ang = (i / 24) * Math.PI * 2 - Math.PI / 2;
      const lx = cx + Math.cos(ang) * ringR;
      const ly = cy + Math.sin(ang) * ringR;
      let r = rgb[0], g = rgb[1], b = rgb[2], alpha = 0.92;

      if (mode === "off") {
        r = 70; g = 80; b = 100; alpha = 0.25;
      } else if (hasHw) {
        r = px[i][0]; g = px[i][1]; b = px[i][2];
        const mag = Math.max(r, g, b);
        alpha = mag > 10 ? 0.95 : 0.20;
      } else if (mode === "rainbow" || mode === "music") {
        const hue = ((i / 24) * 360 + t * 90) % 360;
        const k = (n) => (n + hue / 30) % 12;
        const f = (n) => 255 * (1 - Math.max(-1, Math.min(k(n) - 3, 9 - k(n), 1)));
        r = f(0) | 0; g = f(8) | 0; b = f(4) | 0;
        if (mode === "music") {
          const beat = 0.55 + 0.45 * Math.sin(t * 5.2 + i * 0.65);
          alpha = Math.max(0.35, beat);
        }
      } else if (mode === "breathe") {
        alpha = 0.25 + 0.70 * (0.5 + 0.5 * Math.sin(t * 2.2));
      } else if (mode === "spinner") {
        const head = (t * 14) % 24;
        const dist = (i - head + 24) % 24;
        alpha = dist < 5 ? (1 - dist * 0.18) : 0.14;
      }

      if (alpha > 0.3) {
        const gg = ctx.createRadialGradient(lx, ly, 0, lx, ly, w * 0.08);
        gg.addColorStop(0, `rgba(${r},${g},${b},${alpha * 0.55})`);
        gg.addColorStop(1, "rgba(0,0,0,0)");
        ctx.fillStyle = gg;
        ctx.beginPath();
        ctx.arc(lx, ly, w * 0.08, 0, Math.PI * 2);
        ctx.fill();
      }
      ctx.fillStyle = `rgba(${r},${g},${b},${alpha})`;
      ctx.beginPath();
      ctx.arc(lx, ly, Math.max(2.5, w * 0.032), 0, Math.PI * 2);
      ctx.fill();
    }
  }

  function startWizardAnimLoop() {
    if (wizAnimRaf) return;
    const tick = (now) => {
      const modal = $("wizardModal");
      if (!modal || modal.style.display === "none") {
        wizAnimRaf = null;
        return;
      }
      const t = now * 0.001;
      drawWizAurora(t);
      drawWizOrb(t);
      drawWizMicWave(t);
      drawWizLedRing(t);
      wizAnimRaf = requestAnimationFrame(tick);
    };
    wizAnimRaf = requestAnimationFrame(tick);
  }

  function stopWizardAnimLoop() {
    if (wizAnimRaf) {
      cancelAnimationFrame(wizAnimRaf);
      wizAnimRaf = null;
    }
  }

  let lastAutoBleScanMs = 0;
  function setWizardStep(step) {
    const target = Math.max(1, Math.min(WIZ_TOTAL_STEPS, step));
    const enterCls = target >= wizPrevStep ? "enter-next" : "enter-prev";
    wizPrevStep = target;
    wizStep = target;
    if (wizStep === 1 && Date.now() - lastAutoBleScanMs > 20000) {
      lastAutoBleScanMs = Date.now();
      api("/api/remote/pair", "POST", { force: false }).catch(() => {});
    }
    document.querySelectorAll(".wiz-step-pill").forEach((b) => {
      const s = parseInt(b.dataset.step, 10);
      b.classList.toggle("active", s === wizStep);
      b.classList.toggle("done", s < wizStep);
    });
    for (let i = 1; i <= WIZ_TOTAL_STEPS; i++) {
      const p = $("wizStep" + i);
      if (!p) continue;
      p.classList.remove("active", "enter-next", "enter-prev");
      if (i === wizStep) {
        void p.offsetWidth;
        p.classList.add("active", enterCls);
      }
    }
    if (wizStep === WIZ_TOTAL_STEPS) spawnWizConfetti();
    if ($("btnWizPrev")) $("btnWizPrev").style.visibility = wizStep > 1 ? "visible" : "hidden";
    if ($("btnWizNext")) $("btnWizNext").style.display = wizStep < WIZ_TOTAL_STEPS ? "inline-flex" : "none";
    if ($("btnWizFinish")) $("btnWizFinish").style.display = wizStep === WIZ_TOTAL_STEPS ? "inline-flex" : "none";
    if ($("wizProgressText")) $("wizProgressText").textContent = `Экран ${wizStep} из ${WIZ_TOTAL_STEPS}`;
    if ($("wizFooterFill")) $("wizFooterFill").style.width = `${Math.round((wizStep / WIZ_TOTAL_STEPS) * 100)}%`;
    if (state) syncWizardTelemetry(state, false);
  }

  function parseWizardHashStep() {
    const m = /^#wizard(?:\/([1-7]))?$/i.exec(location.hash || "");
    return m ? (parseInt(m[1], 10) || 1) : 0;
  }

  function openWizard(step = 1) {
    wizPopulatedOnce = false;
    wizPrevStep = step;
    if (state) syncWizardTelemetry(state, true);
    if ($("wizardModal")) $("wizardModal").style.display = "flex";
    setWizardStep(step);
    startWizardAnimLoop();
  }

  function closeWizard(rememberDismiss = true) {
    stopWizardAnimLoop();
    if ($("wizardModal")) $("wizardModal").style.display = "none";
    if (rememberDismiss) sessionStorage.setItem("os_wiz_dismissed", "1");
    if (state?.postOtaWizardPending) {
      state.postOtaWizardPending = false;
      state.postOtaWizardVersion = "";
      api("/api/setup/dismiss_ota_wizard", "POST", {}).catch(() => {});
    }
    if (parseWizardHashStep()) {
      try { history.replaceState(null, "", location.pathname + location.search); } catch {}
    }
  }

  function syncWizardTelemetry(d, populateFields) {
    const hw = d.hardware || {};
    const cfg = d.config || {};
    const ast = d.assistant || {};
    const ww = ast.wakeWord || {};
    const m = d.music || {};
    const fw = d.firmwareVersion || "1.6.3";

    if ($("wizFwBadge")) {
      $("wizFwBadge").textContent = `v${fw}`;
    }
    if ($("wizOtaBadge") && $("wizOtaBanner")) {
      const postOta = !!d.postOtaWizardPending;
      const postVer = d.postOtaWizardVersion || fw;
      $("wizOtaBadge").style.display = postOta ? "inline-flex" : "none";
      $("wizOtaBadge").textContent = `OTA v${postVer}`;
      $("wizOtaBanner").style.display = postOta ? "block" : "none";
      if (postOta) {
        $("wizOtaBanner").textContent = `✨ Обновление до OpenStation Max v${postVer} успешно установлено! Просмотрите новые возможности: активный ADB Агент, обновлённая Алиса и 24-LED визуализация.`;
      }
    }
    if ($("sysAutoInfo")) {
      $("sysAutoInfo").textContent = `Версия v${fw} · Все службы работают автономно внутри станции (${d.ip || "local"}).`;
    }
    if ($("wizDiagIp")) $("wizDiagIp").textContent = d.ip || "127.0.0.1";
    if ($("wizDiagMdns")) $("wizDiagMdns").textContent = `${d.hostname || "station-max"}.local`;
    if ($("wizDiagHw")) {
      $("wizDiagHw").textContent = hw.fb5Present ? "Активен" : "Отключён";
    }
    const cleanAstName = (cfg.assistant?.name || "").trim() || "Алиса";
    const isAlisaWizard = cleanAstName.toLowerCase() === "алиса" || cleanAstName.toLowerCase() === "alisa";
    const wizAvail = Array.isArray(ww.availablePhrases) ? ww.availablePhrases : null;
    const wizHasStock = isAlisaWizard && (!wizAvail || wizAvail.length > 0);
    const wizStockActive = isAlisaWizard && ww.engine === "stock";
    const activePhrase = (ww.phrase === "яндекс" || cfg.assistant?.wakePhrase === "yandex") ? "яндекс" : "алиса";
    const activePhraseName = wizStockActive && activePhrase === "яндекс" ? "Яндекс" : cleanAstName;
    lastWakeWordStatus = ww;
    syncWakePhraseUI(cfg.assistant?.wakePhrase || "alisa");
    syncWakePhraseVisibility(wizHasStock);
    if ($("wizDiagMic")) {
      $("wizDiagMic").textContent = ww.ready
        ? (ww.stockFallback ? `Фраза «${cleanAstName}» (резерв KWS)` : `Фраза «${activePhraseName}» активна`)
        : (ww.status || "Выкл");
    }
    if ($("wizWakeLabel")) {
      $("wizWakeLabel").textContent = wizStockActive
        ? `Откликаться на «${activePhraseName}» (штатный Yandex-споттер)`
        : (ww.stockFallback
          ? `Откликаться на слово «${cleanAstName}» (резерв OpenStation KWS)`
          : `Откликаться на слово «${cleanAstName}»`);
    }
    document.querySelectorAll("#wizNamePresets .seg").forEach((b) => {
      b.classList.toggle("active", (b.dataset.aname || "").toLowerCase() === cleanAstName.toLowerCase());
    });

    const curPrompt = (cfg.assistant?.systemPrompt || "").trim();
    syncPromptPresetButtons(curPrompt);

    const agentOn = cfg.assistant ? (cfg.assistant.agentMode !== false) : true;
    if ($("wizAgentStep1")) $("wizAgentStep1").checked = agentOn;
    if ($("wizAgentStep2")) $("wizAgentStep2").checked = agentOn;
    if ($("wizAgentHint")) {
      $("wizAgentHint").textContent = agentOn
        ? "Ассистент управляет ADB, настройками Android TV и может сам перезагружать демон."
        : "Агентный ADB-режим выключен (оставлены базовые инструменты и музыка).";
    }

    const monoRms = Math.round(Number(d.audio?.engine?.monoRms || 0));
    if ($("wizMicLiveText")) {
      $("wizMicLiveText").textContent = `${monoRms} RMS · ${micInputLabel(d.audio?.engine)}`;
    }

    const dnsHost = d.privateDnsHost || "";
    const dnsMode = d.privateDnsMode || "";
    if ($("dnsCurrent")) $("dnsCurrent").textContent = dnsMode === "hostname" && dnsHost
      ? `Текущая настройка: ${dnsHost}` : dnsMode === "off"
        ? "Текущая настройка: защищённый DNS выключен" : "Текущая настройка: DNS системы (авто)";
    if ($("sysDnsChoice") && !$('sysDnsChoice').dataset.initialized) {
      const choice = $("sysDnsChoice");
      const known = Array.from(choice.options).some(o => o.value === dnsHost);
      choice.value = dnsMode === "hostname" ? (known ? dnsHost : "custom") : (dnsMode === "off" ? "off" : "opportunistic");
      if (dnsMode === "hostname" && !known && $("sysDnsHost")) $("sysDnsHost").value = dnsHost;
      if ($("sysDnsHost")) $("sysDnsHost").hidden = choice.value !== "custom";
      choice.dataset.initialized = "1";
      setTimeout(() => checkDnsLatency(false), 0);
    }
    if ($("wizDnsHost") && document.activeElement !== $("wizDnsHost") && dnsHost) {
      $("wizDnsHost").value = dnsHost;
    }
    if ($("wizNetBanner")) {
      const dnsLabel = dnsMode === "hostname" && dnsHost ? `DNS: ${dnsHost}` : dnsMode === "off" ? "Защищённый DNS: выкл." : "DNS: авто";
      $("wizNetBanner").textContent = `✓ Автономный режим · IP: ${d.ip || "local"} · ${dnsLabel}`;
    }

    const ota = d.ota || {};
    if ($("wizOtaLiveText")) {
      let otaMsg = `Текущая версия: v${fw} · Нажмите «Проверить обновления».`;
      let otaCls = "status-banner";
      if (ota.busy) {
        otaMsg = `⏳ ${ota.progressText || "Загрузка обновления..."} (${ota.progressPct || 0}%)`;
        otaCls = "status-banner warn";
      } else if (ota.updateAvailable && ota.latestVersion) {
        otaMsg = `⬇ Доступна новая версия v${ota.latestVersion}${ota.changelog ? ": " + ota.changelog : ""}`;
        otaCls = "status-banner live";
      } else if (ota.error) {
        otaMsg = `⚠ ${ota.error}`;
        otaCls = "status-banner warn";
      } else if (ota.state === "up_to_date") {
        otaMsg = `✓ Установлена актуальная версия OpenStation Max v${fw}`;
        otaCls = "status-banner ok";
      }
      $("wizOtaLiveText").textContent = otaMsg;
      $("wizOtaLiveText").className = otaCls;
    }
    if ($("btnWizOtaInstall")) {
      $("btnWizOtaInstall").style.display = (ota.updateAvailable && !ota.busy) ? "inline-flex" : "none";
    }
    if ($("btnWizOtaCheck")) {
      $("btnWizOtaCheck").disabled = !!ota.busy;
    }

    if ($("wizMusicBanner")) {
      if (m.hasAuth) {
        $("wizMusicBanner").textContent = `✓ Яндекс Музыка: @${m.userLogin || "OK"}${m.hasPlus ? " · Плюс" : ""}`;
        $("wizMusicBanner").className = "status-banner ok";
      } else {
        $("wizMusicBanner").textContent = "Аккаунт Яндекс Музыки не подключён — выполните вход по QR-коду на экране станции (ТВ).";
        $("wizMusicBanner").className = "status-banner warn";
      }
    }

    const curColor = ($("wizRingColor")?.value || cfg.ledRing?.color || "#00d4ff").toLowerCase();
    document.querySelectorAll("#wizColorSwatches .wiz-swatch").forEach((b) => {
      b.classList.toggle("active", (b.dataset.color || "").toLowerCase() === curColor);
    });
    if ($("wizLedStudioTitle")) {
      const bri = hw.effectiveBrightness ?? cfg.ledRing?.brightness;
      $("wizLedStudioTitle").textContent = bri !== undefined ? `24 RGB LED Кольцо · Яркость ${Math.round((bri / 255) * 100)}%` : "24 RGB LED Кольцо";
    }

    if ($("wizChecklist")) {
      const hasKey = !!(cfg.assistant?.geminiApiKeySet || cfg.assistant?.geminiApiKey);
      const checks = [
        { ok: true, txt: "Станция в сети и готова" },
        { ok: hasKey, txt: hasKey ? `Ключ (${cleanAstName} / Gemini) задан` : `Ключ ассистента не задан (Шаг 2)` },
        { ok: !!ww.ready, txt: ww.ready ? `Отклик на фразу «${cleanAstName}» активен` : "Отклик на фразу выключен" },
        { ok: agentOn, txt: agentOn ? "Агент ADB активен по стоку" : "Агент ADB выключен (опц.)" },
        { ok: !!m.hasAuth, txt: m.hasAuth ? `Яндекс Музыка: @${m.userLogin || "OK"}` : "Яндекс Музыка: без входа (опц.)" }
      ];
      // textContent: userLogin comes from the music account and must not be parsed as HTML
      $("wizChecklist").replaceChildren(...checks.map((c) => {
        const el = document.createElement("div");
        el.className = `wiz-check-item ${c.ok ? "ok" : "warn"}`;
        el.textContent = `${c.ok ? "✓" : "•"} ${c.txt}`;
        return el;
      }));
    }

    if ((populateFields || !wizPopulatedOnce) && cfg.assistant) {
      wizPopulatedOnce = true;
      if ($("wizName") && document.activeElement !== $("wizName")) $("wizName").value = cfg.assistant.name || "Алиса";
      if ($("wizApiKey")) {
        $("wizApiKey").value = "";
        $("wizApiKey").placeholder = cfg.assistant.geminiApiKeySet
          ? "Ключ уже сохранён; пустое поле оставляет его без изменений"
          : "AIza... или AQ.Ab8...";
      }
      if ($("wizProxyHost") && document.activeElement !== $("wizProxyHost")) {
      $("wizProxyHost").value = cfg.assistant.geminiProxyHost !== undefined ? cfg.assistant.geminiProxyHost : "smart-auto";
      }
      if ($("wizVoice") && document.activeElement !== $("wizVoice")) $("wizVoice").value = cfg.assistant.voiceName || "Aoede";
      if ($("wizWakeEn")) $("wizWakeEn").checked = cfg.assistant.wakeWordEnabled !== false;
      if ($("wizAudioRoute") && document.activeElement !== $("wizAudioRoute")) {
        $("wizAudioRoute").value = cfg.audio?.outputRoute || "auto";
        if (!$("wizAudioRoute").value) $("wizAudioRoute").value = "auto";
      }
      if ($("wizEqPreset") && document.activeElement !== $("wizEqPreset")) {
        $("wizEqPreset").value = cfg.audio?.eqPreset || "balanced";
        if (!$("wizEqPreset").value) $("wizEqPreset").value = "balanced";
      }
      if ($("wizRingMode") && document.activeElement !== $("wizRingMode")) $("wizRingMode").value = cfg.ledRing?.mode || "music";
      if ($("wizRingColor") && document.activeElement !== $("wizRingColor")) $("wizRingColor").value = cfg.ledRing?.color || "#00d4ff";
      if ($("wizAutoBri")) $("wizAutoBri").checked = cfg.ledRing?.autoBrightness !== false;
      if ($("wizActShort") && document.activeElement !== $("wizActShort")) $("wizActShort").value = cfg.input?.actionShort || "assistant";
    }
  }

  async function toggleAgentFromWizard(enabled, sourceEl) {
    try {
      const res = await api("/api/assistant/agent_mode", "POST", { enabled });
      const active = !!res.agentMode;
      if (state?.config?.assistant) state.config.assistant.agentMode = active;
      if (state?.assistant) state.assistant.agentMode = active;
      if ($("astAgentMode")) $("astAgentMode").checked = active;
      if ($("wizAgentStep1")) $("wizAgentStep1").checked = active;
      if ($("wizAgentStep2")) $("wizAgentStep2").checked = active;
      if (state) syncWizardTelemetry(state, false);
      showToast(active
        ? "Режим Агента включён — Алиса управляет ADB и может перезагружать демон"
        : "Режим Агента выключен");
    } catch (e) {
      if (sourceEl) sourceEl.checked = !enabled;
      showToast("Не удалось переключить Режим Агента: " + (e?.message || e), true);
    }
  }

  async function pushPartialConfig(patch, toastMsg = "") {
    try {
      const next = await api("/api/config", "POST", patch);
      applyTelemetry(next, true);
      if (toastMsg) showToast(toastMsg);
      return next;
    } catch (e) {
      showToast("Ошибка применения настроек: " + e.message, true);
      return null;
    }
  }

  async function applyDnsHost(hostVal) {
    const clean = (hostVal || "").trim();
    try {
      const payload = clean ? { mode: "hostname", host: clean } : { mode: "opportunistic", host: "" };
      const r = await api("/api/system/dns", "POST", payload);
      showToast(r.ok ? `DNS обновлён: ${r.host || r.mode}` : "Не удалось обновить DNS", !r.ok);
      const s = await api("/api/status");
      applyTelemetry(s, false);
    } catch (e) {
      showToast("Ошибка DNS: " + e.message, true);
    }
  }

  if ($("btnTestAlice")) {
    $("btnTestAlice").addEventListener("click", () => {
      runGeminiLiveTest($("astApiKey")?.value.trim(), "gemini-3.8-live", $("astProxyHost")?.value.trim(), $("aliceTestResult"));
    });
  }
  if ($("btnOpenWizard")) $("btnOpenWizard").addEventListener("click", () => openWizard(1));
  if ($("btnSmartOpenWizard")) $("btnSmartOpenWizard").addEventListener("click", () => openWizard(1));
  if ($("btnWizClose")) $("btnWizClose").addEventListener("click", () => closeWizard(true));
  if ($("btnWizSkip")) $("btnWizSkip").addEventListener("click", () => closeWizard(true));
  document.querySelectorAll(".wiz-step-pill").forEach((b) => {
    b.addEventListener("click", () => setWizardStep(parseInt(b.dataset.step, 10) || 1));
  });
  if ($("btnWizPrev")) $("btnWizPrev").addEventListener("click", () => setWizardStep(wizStep - 1));
  if ($("btnWizNext")) $("btnWizNext").addEventListener("click", () => setWizardStep(wizStep + 1));

  if ($("btnWizToggleKey")) {
    $("btnWizToggleKey").addEventListener("click", () => {
      const inp = $("wizApiKey");
      if (!inp) return;
      const show = inp.type === "password";
      inp.type = show ? "text" : "password";
      $("btnWizToggleKey").textContent = show ? "Скрыть" : "Показать";
    });
  }
  const dnsChoices = {"dns.comss.one": "Comss DNS", "one.one.one.one": "Cloudflare", "dns.google": "Google", "dns.quad9.net": "Quad9"};
  if ($("sysDnsChoice")) $("sysDnsChoice").addEventListener("change", () => {
    $("sysDnsHost").hidden = $("sysDnsChoice").value !== "custom";
  });
  if ($("btnApplyDns")) $("btnApplyDns").addEventListener("click", async () => {
    const choice = $("sysDnsChoice").value;
    if (choice === "custom") {
      const host = $("sysDnsHost").value.trim();
      if (!host) { showToast("Укажите адрес DNS-сервера", true); return; }
      await applyDnsHost(host);
    } else if (choice === "off" || choice === "opportunistic") {
      try {
        const r = await api("/api/system/dns", "POST", { mode: choice });
        showToast(r.ok ? "Настройки DNS сохранены" : "Не удалось обновить DNS", !r.ok);
        applyTelemetry(await api("/api/status"), false);
      } catch (e) { showToast("Ошибка DNS: " + e.message, true); }
    } else await applyDnsHost(choice);
  });
  async function checkDnsLatency(chooseFastest) {
    const info = $("dnsProbeInfo");
    $("btnTestDns").disabled = $("btnFastestDns").disabled = true;
    info.textContent = "Проверяем пинг от станции…";
    try {
      const times = await api("/api/system/dns/latency");
      const choice = $("sysDnsChoice");
      for (const option of choice.options) {
        if (Object.prototype.hasOwnProperty.call(dnsChoices, option.value)) {
          const ms = times[option.value];
          option.textContent = `${dnsChoices[option.value]} — ${Number.isFinite(ms) && ms >= 0 ? `${ms} мс` : "нет ответа на пинг"}`;
        }
      }
      const available = Object.keys(dnsChoices).filter(host => Number.isFinite(times[host]) && times[host] >= 0).sort((a, b) => times[a] - times[b]);
      if (!available.length) { info.textContent = "Нет ответа на пинг. Выберите DNS вручную или проверьте сеть."; return; }
      info.textContent = "Пинг проверен. Некоторые DNS могут не отвечать на пинг, даже если работают.";
      if (chooseFastest) {
        choice.value = available[0];
        $("sysDnsHost").hidden = true;
        await applyDnsHost(available[0]);
      }
    } catch (e) { info.textContent = "Не удалось проверить пинг: " + e.message; }
    finally { $("btnTestDns").disabled = $("btnFastestDns").disabled = false; }
  }
  if ($("btnTestDns")) $("btnTestDns").addEventListener("click", () => checkDnsLatency(false));
  if ($("btnFastestDns")) $("btnFastestDns").addEventListener("click", () => checkDnsLatency(true));
  if ($("btnWizApplyDns") && $("wizDnsHost")) $("btnWizApplyDns").addEventListener("click", () => applyDnsHost($("wizDnsHost").value));
  ["wizAgentStep1", "wizAgentStep2"].forEach((id) => {
    if ($(id)) {
      $(id).addEventListener("change", () => toggleAgentFromWizard($(id).checked, $(id)));
    }
  });

  function buildWizAssistantPatch() {
    const key = $("wizApiKey")?.value.trim() || "";
    const astPatch = {
      enabled: true,
      mode: "gemini",
      name: ($("wizName")?.value.trim()) || "Алиса",
      wakePhrase: getWakePhrase(),
      geminiProxyHost: $("wizProxyHost")?.value.trim() ?? "smart-auto",
      voiceName: $("wizVoice")?.value || "Aoede",
      wakeWordEnabled: $("wizWakeEn") ? $("wizWakeEn").checked : true,
      agentMode: $("wizAgentStep2") ? $("wizAgentStep2").checked : ($("wizAgentStep1") ? $("wizAgentStep1").checked : true)
    };
    if (key) astPatch.geminiApiKey = key;
    return astPatch;
  }

  document.querySelectorAll("#wizPromptPresets .seg, #astPromptPresets .seg").forEach((b) => {
    b.addEventListener("click", async () => {
      const p = WIZ_PROMPTS[b.dataset.preset];
      if (!p) return;
      if ($("astPrompt")) $("astPrompt").value = p;
      syncPromptPresetButtons(p);
      await pushPartialConfig({ assistant: { systemPrompt: p } }, `Характер «${b.textContent}» сохранён`);
    });
  });
  if ($("astPrompt")) {
    $("astPrompt").addEventListener("input", () => syncPromptPresetButtons($("astPrompt").value));
  }

  if ($("btnWizTestGemini")) {
    $("btnWizTestGemini").addEventListener("click", async () => {
      const astPatch = buildWizAssistantPatch();
      await pushPartialConfig({ assistant: astPatch });
      await runGeminiLiveTest(astPatch.geminiApiKey || "", "gemini-3.8-live", astPatch.geminiProxyHost, $("wizGeminiBanner"));
    });
  }
  if ($("btnWizTestSay")) $("btnWizTestSay").addEventListener("click", triggerAlice);

  ["wizName", "wizVoice", "wizWakeEn"].forEach((id) => {
    if ($(id)) {
      $(id).addEventListener("change", () => pushPartialConfig({
        assistant: buildWizAssistantPatch()
      }, "Параметры ассистента сохранены"));
    }
  });
  if ($("btnWizYmOAuth")) {
    $("btnWizYmOAuth").addEventListener("click", async () => {
      const inp = $("wizYmToken");
      const tok = inp ? inp.value.trim() : "";
      if (!tok) return;
      await sendMusicCmd({ cmd: "music_oauth", token: tok, autoPlay: false }, "Токен передан Яндекс Музыке");
      if (inp) inp.value = "";
    });
  }
  if ($("btnWizYmTv")) $("btnWizYmTv").addEventListener("click", () => sendMusicCmd({ cmd: "launch_music", tab: "auth", openLogin: true }, "Запрос на открытие входа на ТВ отправлен"));
  if ($("btnWizYmWave")) $("btnWizYmWave").addEventListener("click", () => sendMusicCmd({ cmd: "play_my_wave", mood: "any", openUi: false }, "Запускаем Мою волну..."));
  if ($("wizAudioRoute")) {
    $("wizAudioRoute").addEventListener("change", () => pushPartialConfig({ audio: { outputRoute: $("wizAudioRoute").value } }, "Выход звука обновлён"));
  }
  if ($("wizEqPreset")) {
    $("wizEqPreset").addEventListener("change", () => pushPartialConfig({ audio: { eqPreset: $("wizEqPreset").value } }, "Эквалайзер применён"));
  }
  document.querySelectorAll("#wizColorSwatches .wiz-swatch").forEach((b) => {
    b.addEventListener("click", () => {
      const col = b.dataset.color || "#00d4ff";
      if ($("wizRingColor")) $("wizRingColor").value = col;
      pushPartialConfig({
        ledRing: {
          mode: $("wizRingMode")?.value || "music",
          color: col,
          autoBrightness: $("wizAutoBri") ? $("wizAutoBri").checked : true
        }
      }, "Цвет кольца обновлён");
    });
  });
  ["wizRingMode", "wizRingColor", "wizAutoBri"].forEach((id) => {
    if ($(id)) {
      $(id).addEventListener("change", () => pushPartialConfig({
        ledRing: {
          mode: $("wizRingMode")?.value || "music",
          color: $("wizRingColor")?.value || "#00d4ff",
          autoBrightness: $("wizAutoBri") ? $("wizAutoBri").checked : true
        }
      }, "Подсветка обновлена"));
    }
  });
  if ($("wizActShort")) {
    $("wizActShort").addEventListener("change", () => pushPartialConfig({ input: { actionShort: $("wizActShort").value } }, "Кнопка обновлена"));
  }
  if ($("btnWizCecWake")) {
    $("btnWizCecWake").addEventListener("click", () => api("/api/remote/cec", "POST", { cmd: "wakeup" }).then(() => showToast("Команда включения отправлена ТВ")).catch(() => {}));
  }
  if ($("btnWizStartupShow")) {
    $("btnWizStartupShow").addEventListener("click", async () => {
      try {
        await api("/api/system/startup_show", "POST", {});
        showToast("Запущено стартовое свето-звуковое шоу!");
      } catch (e) { showToast("Ошибка запуска шоу: " + e.message, true); }
    });
  }
  if ($("btnWizOtaCheck")) {
    $("btnWizOtaCheck").addEventListener("click", async () => {
      try {
        await api("/api/ota/check", "POST", {});
        showToast("Проверяем обновления на GitHub...");
      } catch (e) { showToast("Ошибка проверки OTA: " + e.message, true); }
    });
  }
  if ($("btnWizOtaInstall")) {
    $("btnWizOtaInstall").addEventListener("click", async () => {
      try {
        otaPendingRestart = true;
        await api("/api/ota/install", "POST", { force: false });
        showToast("Запущена установка обновления OTA...");
      } catch (e) { otaPendingRestart = false; showToast("Ошибка установки OTA: " + e.message, true); }
    });
  }
  if ($("btnWizRestartDaemon")) {
    $("btnWizRestartDaemon").addEventListener("click", async () => {
      if (!confirm("Перезапустить демон openstationd сейчас? Панель восстановится через 2-3 секунды.")) return;
      try {
        await api("/api/system/restart_daemon", "POST", {});
        showToast("Демон перезагружается...");
      } catch (e) { showToast("Ошибка рестарта: " + e.message, true); }
    });
  }
  if ($("btnWizFinish")) {
    $("btnWizFinish").addEventListener("click", async () => {
      await pushPartialConfig({
        assistant: buildWizAssistantPatch(),
        ledRing: {
          mode: $("wizRingMode")?.value || "music",
          color: $("wizRingColor")?.value || "#00d4ff",
          autoBrightness: $("wizAutoBri") ? $("wizAutoBri").checked : true
        },
        audio: {
          outputRoute: $("wizAudioRoute")?.value || "auto",
          eqPreset: $("wizEqPreset")?.value || "balanced"
        },
        input: {
          actionShort: $("wizActShort")?.value || "assistant"
        }
      });
      try {
        const next = await api("/api/setup/complete", "POST", {});
        applyTelemetry(next, true);
        closeWizard(true);
        showToast("Настройка завершена! Станция готова.");
      } catch (e) {
        showToast("Ошибка завершения: " + e.message, true);
      }
    });
  }
  async function copyTextSafe(txt, inpEl) {
    if (navigator.clipboard && window.isSecureContext) {
      try {
        await navigator.clipboard.writeText(txt);
        return true;
      } catch {}
    }
    if (inpEl) {
      inpEl.focus();
      inpEl.select();
      try { inpEl.setSelectionRange(0, txt.length); } catch {}
      try {
        if (document.execCommand("copy")) return true;
      } catch {}
    }
    return false;
  }

  async function runWifiAdbAction(payload, toastOk) {
    wifiAdbBusy = true;
    applyWifiAdbState(state?.wifiAdb || {}, state || {});
    try {
      const next = await api("/api/system/wifi_adb", "POST", payload);
      wifiAdbBusy = false;
      applyTelemetry(next, false);
      if (toastOk) showToast(toastOk, next.ok === false);
    } catch (e) {
      wifiAdbBusy = false;
      applyWifiAdbState(state?.wifiAdb || {}, state || {});
      showToast("Ошибка Wi-Fi ADB: " + e.message, true);
    }
  }

  if ($("btnCopyWifiAdb")) {
    $("btnCopyWifiAdb").addEventListener("click", async () => {
      const inp = $("wifiAdbCmd");
      const val = inp ? inp.value.trim() : "";
      if (!val) return;
      const copied = await copyTextSafe(val, inp);
      showToast(copied ? ("Скопировано: " + val) : "Выделите и скопируйте команду вручную", !copied);
    });
  }
  if ($("wifiAdbEnabled")) {
    $("wifiAdbEnabled").addEventListener("change", () => {
      const on = $("wifiAdbEnabled").checked;
      runWifiAdbAction(
        { enabled: on, action: on ? "enable" : "disable" },
        on ? "Wi-Fi ADB включён" : "Wi-Fi ADB отключён (оставлен USB ADB)"
      );
    });
  }
  if ($("btnRestartWifiAdb")) {
    $("btnRestartWifiAdb").addEventListener("click", () => {
      runWifiAdbAction({ action: "restart" }, "Служба ADB перезапущена");
    });
  }
  if ($("btnRestartDaemon")) {
    $("btnRestartDaemon").addEventListener("click", async () => {
      if (!confirm("Перезапустить службу управления на станции? Связь восстановится через 2-3 секунды.")) return;
      try {
        await api("/api/system/restart_daemon", "POST", {});
        showToast("Служба перезапускается...");
      } catch (e) { showToast("Ошибка: " + e.message, true); }
    });
  }
  if ($("btnRebootStation")) {
    $("btnRebootStation").addEventListener("click", async () => {
      if (!confirm("Перезагрузить Яндекс Станцию Макс? Панель станет недоступна на ~30 секунд.")) return;
      try {
        await api("/api/system/reboot", "POST", {});
        showToast("Станция перезагружается...");
      } catch (e) { showToast("Ошибка: " + e.message, true); }
    });
  }
  if ($("badgeOtaUpdate")) {
    $("badgeOtaUpdate").addEventListener("click", () => {
      switchTab("system");
      $("otaCard")?.scrollIntoView({ behavior: "smooth", block: "center" });
    });
  }
  if ($("btnOtaCheck")) {
    $("btnOtaCheck").addEventListener("click", async () => {
      try {
        const body = {
          repo: ($("otaRepo")?.value.trim()) || "Olegu621/OPENSTATION",
          branch: ($("otaBranch")?.value.trim()) || "main",
          autoCheck: $("otaAutoCheck") ? $("otaAutoCheck").checked : true,
          syncBootanimation: $("otaSyncBootanim") ? $("otaSyncBootanim").checked : true
        };
        const r = await api("/api/ota/check", "POST", body);
        if (r.ota) applyOtaState(r.ota, state?.firmwareVersion);
        showToast(r.started ? "Проверка обновлений..." : "Проверка уже выполняется");
      } catch (e) { showToast("Ошибка проверки обновлений: " + e.message, true); }
    });
  }
  async function triggerOtaInstall(force) {
    const ver = state?.ota?.latestVersion || state?.firmwareVersion || "1.5.0";
    const msg = force
      ? `Скачать и переустановить OpenStation Max v${ver}? Все ваши ключи и настройки сохранятся.`
      : `Установить обновление OpenStation Max v${ver}? Все ваши ключи и настройки сохранятся.`;
    if (!confirm(msg)) return;
    otaPendingRestart = true;
    try {
      const r = await api("/api/ota/install", "POST", { force: !!force });
      if (r.ok === false) otaPendingRestart = false;
      if (r.ota) applyOtaState(r.ota, state?.firmwareVersion);
      showToast(r.message || "Запуск обновления...", !r.ok);
    } catch (e) {
      otaPendingRestart = false;
      showToast("Ошибка обновления: " + e.message, true);
    }
  }
  if ($("btnOtaInstall")) $("btnOtaInstall").addEventListener("click", () => triggerOtaInstall(false));
  if ($("btnOtaReinstall")) $("btnOtaReinstall").addEventListener("click", () => triggerOtaInstall(true));
  window.addEventListener("hashchange", () => {
    const hs = parseWizardHashStep();
    if (hs) openWizard(hs);
  });

  // ===================== Связь со станцией и мини-плеер =====================
  let connFails = 0;
  function setConn(ok) {
    connFails = ok ? 0 : connFails + 1;
    const off = !ok && connFails >= 2;
    if ($("connBanner")) $("connBanner").hidden = !off;
    if ($("connBannerSub")) $("connBannerSub").textContent = `Переподключаемся… попытка ${connFails} · ${location.host}`;
    document.body.classList.toggle("offline", off);
  }
  if ($("btnConnRetry")) $("btnConnRetry").addEventListener("click", () => { connectWs(); init(); });

  document.querySelectorAll("[data-mini-cmd]").forEach((b) => {
    b.addEventListener("click", () => {
      const t = $(b.dataset.miniCmd);
      if (t) t.click();
    });
  });

  if ($("btnMusicTvLogin")) {
    $("btnMusicTvLogin").addEventListener("click", () => sendMusicCmd({ cmd: "launch_music", tab: "auth", openLogin: true }, "Экран входа открыт на ТВ"));
  }
  if ($("btnPushMusicLoginOAuth")) {
    $("btnPushMusicLoginOAuth").addEventListener("click", async () => {
      const inp = $("musicLoginOAuthInput");
      const tok = inp ? inp.value.trim() : "";
      if (!tok) return;
      await sendMusicCmd({ cmd: "music_oauth", token: tok, autoPlay: true }, "Токен передан плееру");
      if (inp) inp.value = "";
    });
  }

  // ===================== Имя ассистента и подбор голоса =====================
  const MALE_VOICES = new Set(["Puck", "Charon", "Fenrir", "Orus"]);
  const FEMALE_VOICES = new Set(["Aoede", "Kore", "Leda", "Zephyr"]);
  function inferGender(name, hintGender) {
    if (hintGender === "male" || hintGender === "female") return hintGender;
    const l = (name || "").trim().toLowerCase();
    if (/(джарвис|jarvis|макс|max|марк|алекс|олег|иван|арт[её]м|борис|денис|виктор|дмитрий|орион|orion|кеша|барс|робот|greg|alex|steve|tony|ultron|david|john|peter)/.test(l)) return "male";
    return "female";
  }
  async function applyAssistantNameChoice(rawName, hintGender) {
    const clean = (rawName || "").trim() || "Алиса";
    const wantGender = inferGender(clean, hintGender);
    if ($("astName")) $("astName").value = clean;
    if ($("wizName")) $("wizName").value = clean;

    const voiceEl = $("astVoice") || $("wizVoice");
    let voice = voiceEl ? voiceEl.value : "Aoede";
    if (wantGender === "male" && !MALE_VOICES.has(voice)) voice = "Charon";
    if (wantGender === "female" && !FEMALE_VOICES.has(voice)) voice = "Aoede";
    if ($("astVoice")) $("astVoice").value = voice;
    if ($("wizVoice")) $("wizVoice").value = voice;

    syncAssistantNameLabels(clean);
    await pushPartialConfig({
      assistant: {
        name: clean,
        voiceName: voice
      }
    }, `Ассистент переименован: «${clean}»`);
  }
  if ($("btnApplyAstName")) {
    $("btnApplyAstName").addEventListener("click", () => applyAssistantNameChoice($("astName")?.value, ""));
  }
  if ($("astName")) {
    $("astName").addEventListener("keydown", (e) => {
      if (e.key === "Enter") {
        e.preventDefault();
        applyAssistantNameChoice($("astName").value, "");
      }
    });
  }
  document.querySelectorAll("#astNamePresets .seg, #wizNamePresets .seg").forEach((b) => {
    b.addEventListener("click", () => applyAssistantNameChoice(b.dataset.aname, b.dataset.gender));
  });
  document.querySelectorAll("#astWakePhrasePresets .seg, #wizWakePhrasePresets .seg").forEach((b) => {
    b.addEventListener("click", async () => {
      if (b.disabled) return;
      const phrase = b.dataset.wphrase === "yandex" ? "yandex" : "alisa";
      syncWakePhraseUI(phrase);
      syncAssistantNameLabels($("astName")?.value || "Алиса");
      await pushPartialConfig(
        { assistant: { wakePhrase: phrase } },
        `Слово активации: «${phrase === "yandex" ? "Яндекс" : "Алиса"}»`
      );
    });
  });

  // ===================== Таймеры и будильники =====================
  const DAY_RU = ["пн", "вт", "ср", "чт", "пт", "сб", "вс"];
  const SOUND_RU = { wave: "Моя волна", calm: "Спокойная волна", chime: "Мелодия станции" };
  let alarmsCache = [];
  let lastTimersSig = "";

  const pad2 = (n) => String(n).padStart(2, "0");
  const fmtSec = (s) => {
    s = Math.max(0, s | 0);
    const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60;
    return h ? `${h}:${pad2(m)}:${pad2(r)}` : `${pad2(m)}:${pad2(r)}`;
  };
  function daysLabel(days) {
    const d = Array.isArray(days) ? days.map(Boolean) : [];
    const n = d.filter(Boolean).length;
    if (!n) return "один раз";
    if (n === 7) return "каждый день";
    if (n === 5 && d.slice(0, 5).every(Boolean)) return "пн–пт";
    if (n === 2 && d[5] && d[6]) return "сб, вс";
    return DAY_RU.filter((_, i) => d[i]).join(", ");
  }

  async function saveAlarms(list, okMsg) {
    try {
      const r = await api("/api/alarms", "POST", { alarms: list });
      alarmsCache = r.alarms || list;
      lastTimersSig = "";
      renderTimers(Object.assign({}, state || {}, { alarms: alarmsCache }));
      if (okMsg) showToast(okMsg);
    } catch (e) {
      showToast("Будильники: " + e.message, true);
    }
  }

  function renderTimers(d) {
    const box = $("timerList");
    if (!box || !d) return;
    const timers = d.timers || d.activeTimers || [];
    if (Array.isArray(d.alarms)) alarmsCache = d.alarms;
    else if (Array.isArray(d.config?.alarms)) alarmsCache = d.config.alarms;

    const ring = d.ringingAlarm;
    if ($("alarmBanner")) {
      $("alarmBanner").hidden = !ring;
      if (ring) {
        if (ring.isTimer) {
          $("alarmBannerTitle").textContent = "Таймер сработал";
          $("alarmBannerSub").textContent = ring.label || "Время вышло";
          if ($("btnAlarmSnooze")) $("btnAlarmSnooze").textContent = "+5 мин";
        } else {
          $("alarmBannerTitle").textContent = `Будильник ${ring.time || ""}`;
          $("alarmBannerSub").textContent = ring.label || "Звонит сейчас";
          if ($("btnAlarmSnooze")) $("btnAlarmSnooze").textContent = "Отложить 5 мин";
        }
      }
    }

    const sig = JSON.stringify([timers, alarmsCache]);
    if (sig === lastTimersSig) return;
    lastTimersSig = sig;
    box.innerHTML = "";
    if (!timers.length && !alarmsCache.length) {
      box.innerHTML = '<div class="hint">Нет активных таймеров и будильников.</div>';
      return;
    }
    const row = (cls, time, sub, ctrl) => {
      const it = document.createElement("div");
      it.className = "timer-item " + cls;
      const main = document.createElement("div");
      main.className = "t-main";
      const t = document.createElement("span"); t.className = "t-time"; t.textContent = time;
      const s = document.createElement("span"); s.className = "t-sub"; s.textContent = sub;
      main.append(t, s);
      it.append(main, ...ctrl);
      box.appendChild(it);
    };
    timers.forEach((t) => {
      const b = document.createElement("button");
      b.className = "btn btn-sm";
      b.textContent = "Отменить";
      b.addEventListener("click", () => api("/api/timers/cancel", "POST", { id: t.id })
        .then((r) => { lastTimersSig = ""; renderTimers(Object.assign({}, state || {}, { timers: r.timers || [] })); showToast("Таймер отменён"); })
        .catch((e) => showToast("Таймер: " + e.message, true)));
      row("running", fmtSec(t.remainingSec), `Таймер${t.label ? " «" + t.label + "»" : ""} · из ${fmtSec(t.totalSec)}`, [b]);
    });
    alarmsCache.forEach((a, idx) => {
      const sw = document.createElement("input");
      sw.type = "checkbox";
      sw.checked = !!a.enabled;
      sw.setAttribute("aria-label", `Будильник ${a.time}`);
      sw.addEventListener("change", () => {
        const next = alarmsCache.map((x, i) => (i === idx ? Object.assign({}, x, { enabled: sw.checked }) : x));
        saveAlarms(next);
      });
      const del = document.createElement("button");
      del.className = "icon-btn";
      del.setAttribute("aria-label", `Удалить будильник ${a.time}`);
      del.textContent = "✕";
      del.addEventListener("click", () => saveAlarms(alarmsCache.filter((_, i) => i !== idx), "Будильник удалён"));
      row(a.enabled ? "" : "off", a.time || "--:--",
        `Будильник · ${daysLabel(a.days)} · ${SOUND_RU[a.sound] || "Моя волна"}${a.label ? " · " + a.label : ""}`, [sw, del]);
    });
  }

  let tmKind = "timer";
  document.querySelectorAll("#tmKind .seg").forEach((b) => b.addEventListener("click", () => {
    tmKind = b.dataset.kind;
    document.querySelectorAll("#tmKind .seg").forEach((x) => x.classList.toggle("active", x === b));
    if ($("tmTimerFields")) $("tmTimerFields").hidden = tmKind !== "timer";
    if ($("tmAlarmFields")) $("tmAlarmFields").hidden = tmKind !== "alarm";
  }));
  document.querySelectorAll("#alDays .seg").forEach((b) => b.addEventListener("click", () => b.classList.toggle("active")));
  if ($("btnTmAdd")) {
    $("btnTmAdd").addEventListener("click", async () => {
      if (tmKind === "timer") {
        const sec = (parseInt($("tmMin").value, 10) || 0) * 60 + (parseInt($("tmSec").value, 10) || 0);
        if (sec <= 0) { showToast("Укажите время таймера", true); return; }
        try {
          const r = await api("/api/timers", "POST", { seconds: sec, label: $("tmLabel").value.trim() });
          lastTimersSig = "";
          renderTimers(Object.assign({}, state || {}, { timers: r.timers || [] }));
          showToast("Таймер запущен: " + fmtSec(sec));
        } catch (e) { showToast("Таймер: " + e.message, true); }
      } else {
        const time = $("alTime").value;
        if (!/^\d{2}:\d{2}$/.test(time)) { showToast("Укажите время будильника", true); return; }
        const days = [...document.querySelectorAll("#alDays .seg")].map((b) => b.classList.contains("active"));
        saveAlarms(alarmsCache.concat([{ time, days, label: $("alLabel").value.trim(), enabled: true, sound: $("alSound").value }]),
          `Будильник на ${time} · ${daysLabel(days)}`);
      }
    });
  }
  if ($("btnAlarmStop")) $("btnAlarmStop").addEventListener("click", () => api("/api/alarms/stop", "POST", {}).then(() => { $("alarmBanner").hidden = true; }).catch((e) => showToast(e.message, true)));
  if ($("btnAlarmSnooze")) $("btnAlarmSnooze").addEventListener("click", () => api("/api/alarms/snooze", "POST", { minutes: 5 }).then(() => { $("alarmBanner").hidden = true; showToast("Отложено на 5 минут"); }).catch((e) => showToast(e.message, true)));

  // ===================== Умный дом (Home Assistant) =====================
  const HA_ICONS = {
    light: "M9 18h6M10 21h4M12 3a6 6 0 0 0-4 10.5c.8.7 1 1.5 1 2.5h6c0-1 .2-1.8 1-2.5A6 6 0 0 0 12 3z",
    switch: "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM9.5 9v3M14.5 9v3M10 15.5h4",
    fan: "M12 12m-2 0a2 2 0 1 0 4 0 2 2 0 1 0-4 0M12 10c0-4 1-7 4-7s2 5-2 7M14 12c4 0 7 1 7 4s-5 2-7-2M12 14c0 4-1 7-4 7s-2-5 2-7M10 12c-4 0-7-1-7-4s5-2 7 2",
    cover: "M4 4h16M5 4v16M19 4v16M9 4v9M15 4v9",
    climate: "M3 7h18v8H3zM7 19l1-2M12 19v-2M17 19l-1-2",
    vacuum: "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7.5a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3z",
    media_player: "M3 5h18v12H3zM8 21h8"
  };
  const HA_ON = ["on", "open", "opening", "heat", "cool", "heat_cool", "auto", "dry", "fan_only", "cleaning", "playing"];
  let haEntities = [];
  let haRoom = "";
  let haLoading = false;

  function haSetup(show, reason) {
    if ($("haSetupCard")) $("haSetupCard").hidden = !show;
    if ($("haMain")) $("haMain").hidden = show;
    const ha = state?.config?.homeAssistant || {};
    if (show && $("haUrl") && !$("haUrl").value) $("haUrl").value = ha.url || "";
    if (show && reason && reason !== "not_configured") showToast("Home Assistant: " + reason, true);
  }

  async function loadHome() {
    if (haLoading) return;
    haLoading = true;
    try {
      const r = await api("/api/ha/states");
      if (r.ok === false) { haSetup(true, r.error); return; }
      haEntities = Array.isArray(r) ? r : (r.entities || r.states || []);
      haSetup(false);
      if ($("haStatus")) {
        $("haStatus").textContent = `Home Assistant · ${haEntities.length} объектов`;
        $("haStatus").classList.remove("off");
      }
      renderHome();
    } catch (e) {
      haSetup(true, e.message);
    } finally {
      haLoading = false;
    }
  }

  function haStateText(e) {
    const s = e.state;
    if (e.domain === "light" && s === "on") return e.brightness_pct != null ? `Вкл · ${e.brightness_pct}%` : "Вкл";
    if (e.domain === "cover") return { open: "Открыто", closed: "Закрыто", opening: "Открывается", closing: "Закрывается" }[s] || s;
    if (e.domain === "vacuum") return { cleaning: "Убирает", docked: "На базе", returning: "Едет на базу", paused: "Пауза", idle: "Ждёт" }[s] || s;
    if (e.domain === "climate") return s === "off" ? "Выкл" : s;
    if (e.domain === "media_player") return { playing: "Играет", paused: "Пауза", off: "Выкл", idle: "Ждёт" }[s] || s;
    if (s === "unavailable") return "Недоступно";
    return s === "on" ? "Вкл" : s === "off" ? "Выкл" : s;
  }

  function haCall(domain, service, entity_id, tile) {
    if (tile) tile.classList.add("busy");
    return api("/api/ha/service", "POST", { domain, service, entity_id })
      .then(() => setTimeout(loadHome, 600))
      .catch((err) => { showToast("Home Assistant: " + err.message, true); if (tile) tile.classList.remove("busy"); });
  }

  function haToggle(e, tile) {
    const on = HA_ON.includes(e.state);
    if (e.domain === "cover") return haCall("cover", on ? "close_cover" : "open_cover", e.entity_id, tile);
    if (e.domain === "vacuum") return haCall("vacuum", e.state === "cleaning" ? "return_to_base" : "start", e.entity_id, tile);
    if (e.domain === "climate") return haCall("climate", on ? "turn_off" : "turn_on", e.entity_id, tile);
    if (e.domain === "media_player") return haCall("media_player", "media_play_pause", e.entity_id, tile);
    return haCall(e.domain, "toggle", e.entity_id, tile);
  }

  function renderHome() {
    const areas = [...new Set(haEntities.map((e) => e.area).filter(Boolean))];
    if (haRoom && !areas.includes(haRoom)) haRoom = "";
    const inRoom = (e) => !haRoom || e.area === haRoom;

    const rooms = $("haRooms");
    if (rooms) {
      rooms.innerHTML = "";
      ["", ...areas].forEach((a) => {
        const b = document.createElement("button");
        b.className = "seg" + (a === haRoom ? " active" : "");
        b.textContent = a || "Все";
        b.addEventListener("click", () => { haRoom = a; renderHome(); });
        rooms.appendChild(b);
      });
      rooms.hidden = !areas.length;
    }

    const scenes = haEntities.filter((e) => e.domain === "scene" || e.domain === "script");
    if ($("haScenesCard")) $("haScenesCard").hidden = !scenes.length;
    const sc = $("haScenes");
    if (sc) {
      sc.innerHTML = "";
      scenes.forEach((e) => {
        const b = document.createElement("button");
        b.className = "seg";
        b.textContent = e.name || e.entity_id;
        b.addEventListener("click", () => haCall(e.domain, "turn_on", e.entity_id).then(() => showToast("Сценарий: " + (e.name || e.entity_id))));
        sc.appendChild(b);
      });
    }

    const grid = $("haDevices");
    if (grid) {
      grid.innerHTML = "";
      haEntities.filter((e) => HA_ICONS[e.domain] && inRoom(e)).forEach((e) => {
        const on = HA_ON.includes(e.state);
        const b = document.createElement("button");
        b.className = "device" + (on ? " on" : "");
        b.setAttribute("aria-pressed", on ? "true" : "false");
        b.innerHTML = `<svg class="ico" width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="${HA_ICONS[e.domain]}"/></svg>`;
        const meta = document.createElement("span");
        meta.style.cssText = "display:flex;flex-direction:column;gap:2px";
        const n = document.createElement("span"); n.className = "d-name"; n.textContent = e.name || e.entity_id;
        const st = document.createElement("span"); st.className = "d-state"; st.textContent = haStateText(e);
        meta.append(n, st);
        if (e.area && !haRoom) { const ar = document.createElement("span"); ar.className = "d-area"; ar.textContent = e.area; meta.append(ar); }
        b.appendChild(meta);
        if (e.state !== "unavailable") b.addEventListener("click", () => haToggle(e, b));
        grid.appendChild(b);
      });
      if (!grid.children.length) grid.innerHTML = '<div class="hint">Нет управляемых устройств' + (haRoom ? " в этой комнате" : "") + ".</div>";
    }

    const sens = $("haSensors");
    if (sens) {
      sens.innerHTML = "";
      haEntities.filter((e) => (e.domain === "sensor" || e.domain === "binary_sensor") && inRoom(e)).forEach((e) => {
        let val = `${e.state}${e.unit ? " " + e.unit : ""}`;
        if (e.domain === "binary_sensor") {
          const on = e.state === "on";
          val = e.device_class === "motion" ? (on ? "Движение" : "Тихо") : (on ? "Открыто" : "Закрыто");
        }
        const c = document.createElement("div");
        c.className = "sensor";
        const n = document.createElement("span"); n.className = "s-name"; n.textContent = e.name || e.entity_id;
        const v = document.createElement("span"); v.className = "s-val"; v.textContent = val;
        c.append(n, v);
        sens.appendChild(c);
      });
    }
  }

  if ($("btnHaSave")) {
    $("btnHaSave").addEventListener("click", async () => {
      const url = $("haUrl").value.trim().replace(/\/+$/, "");
      const token = $("haToken").value.trim();
      if (!/^https?:\/\//.test(url)) { showToast("Адрес должен начинаться с http:// или https://", true); return; }
      const patch = { homeAssistant: { url } };
      if (token) patch.homeAssistant.token = token;
      const r = await pushPartialConfig(patch, "Home Assistant сохранён");
      if (r) { $("haToken").value = ""; loadHome(); }
    });
  }
  if ($("btnHaReconfig")) $("btnHaReconfig").addEventListener("click", () => haSetup(true));

  // ===================== Wi-Fi / Bluetooth Misc Revision Repair =====================
  let selectedMiscRevision = "";
  let miscModalAutoShown = false;
  let miscFlashInFlight = false;

  function formatMiscRevLabel(rev) {
    if (rev === "0008_00052") return "0008 / 00052 (Intel)";
    if (rev === "00053") return "00053 (Realtek / Unisoc)";
    return "не определена";
  }

  function selectMiscRevision(rev) {
    selectedMiscRevision = (rev === "0008_00052" || rev === "00053") ? rev : "";
    document.querySelectorAll("#miscRevCards .misc-rev-card").forEach((c) => {
      c.classList.toggle("active", c.dataset.rev === selectedMiscRevision);
    });
    if ($("btnMiscFlashConfirm")) {
      $("btnMiscFlashConfirm").disabled = !selectedMiscRevision || miscFlashInFlight;
    }
  }

  function renderMiscDiagIntoModal(mr) {
    if (!mr) return;
    const rec = mr.recommendedRevision || "";
    if ($("miscRec0008")) $("miscRec0008").style.display = rec === "0008_00052" ? "inline-flex" : "none";
    if ($("miscRec00053")) $("miscRec00053").style.display = rec === "00053" ? "inline-flex" : "none";

    const badge = $("miscModalBadge");
    if (badge) {
      if (mr.flashing || miscFlashInFlight) {
        badge.textContent = "Прошивка misc...";
        badge.className = "badge warn";
      } else if (mr.needsRepair) {
        badge.textContent = "Требуется проверка";
        badge.className = "badge warn";
      } else {
        badge.textContent = "Wi-Fi и BT работают";
        badge.className = "badge ok";
      }
    }

    const diag = $("miscDiagBanner");
    if (diag) {
      const chipLabel = mr.chip === "intel" ? "Intel 7265" : (mr.chip === "realtek" ? "Realtek 8822ES" : (mr.chip === "unisoc" ? "Unisoc UWE5621" : "не определён"));
      diag.textContent =
        `Wi-Fi: ${mr.wifiOk ? "OK" : "не работает"} · Bluetooth: ${mr.btOk ? "OK" : "не работает"} · ` +
        `DT: ${mr.currentDtId || "?"} (${formatMiscRevLabel(mr.currentRevision)}) · Чип: ${chipLabel}` +
        (rec ? ` · Рекомендуется: ${formatMiscRevLabel(rec)}` : "");
      diag.className = "status-banner " + (mr.needsRepair ? "warn" : "ok");
    }

    if (!selectedMiscRevision) {
      const fallback = rec || (mr.currentRevision !== "unknown" ? mr.currentRevision : "0008_00052");
      selectMiscRevision(fallback);
    }
  }

  function openMiscRepairModal(mr) {
    const modal = $("miscRepairModal");
    if (!modal) return;
    if (mr) renderMiscDiagIntoModal(mr);
    if ($("miscFlashBanner")) {
      $("miscFlashBanner").style.display = "none";
      $("miscFlashBanner").textContent = "";
    }
    modal.style.display = "flex";
  }

  async function closeMiscRepairModal(sendDismiss) {
    const modal = $("miscRepairModal");
    if (modal) modal.style.display = "none";
    miscModalAutoShown = true;
    try { sessionStorage.setItem("os_misc_dismissed", "1"); } catch {}
    if (sendDismiss) {
      try { await api("/api/misc/dismiss", "POST", {}); } catch {}
    }
  }

  function handleMiscRepairState(mr) {
    if (!mr) return;
    if ($("miscStatusHint")) {
      const chipLabel = mr.chip === "intel" ? "Intel" : (mr.chip === "realtek" ? "Realtek" : (mr.chip === "unisoc" ? "Unisoc" : "не определён"));
      $("miscStatusHint").textContent =
        `DT: ${mr.currentDtId || "?"} · чип: ${chipLabel} · Wi-Fi: ${mr.wifiOk ? "OK" : "Нет"} · BT: ${mr.btOk ? "OK" : "Нет"}`;
    }
    if ($("miscRepairModal")?.style.display === "flex") {
      renderMiscDiagIntoModal(mr);
    }
    let locallyDismissed = false;
    try { locallyDismissed = sessionStorage.getItem("os_misc_dismissed") === "1"; } catch {}
    if (mr.showPopup && !miscModalAutoShown && !locallyDismissed && !miscFlashInFlight) {
      miscModalAutoShown = true;
      selectedMiscRevision = "";
      openMiscRepairModal(mr);
    }
  }

  document.querySelectorAll("#miscRevCards .misc-rev-card").forEach((card) => {
    card.addEventListener("click", () => selectMiscRevision(card.dataset.rev || ""));
  });
  if ($("btnOpenMiscModal")) {
    $("btnOpenMiscModal").addEventListener("click", async () => {
      selectedMiscRevision = "";
      openMiscRepairModal(state?.miscRepair || null);
      try {
        const fresh = await api("/api/misc/status");
        if (state) state.miscRepair = fresh;
        renderMiscDiagIntoModal(fresh);
      } catch {}
    });
  }
  if ($("btnMiscClose")) $("btnMiscClose").addEventListener("click", () => closeMiscRepairModal(true));
  if ($("btnMiscDismiss")) $("btnMiscDismiss").addEventListener("click", () => closeMiscRepairModal(true));
  if ($("btnMiscFlashConfirm")) {
    $("btnMiscFlashConfirm").addEventListener("click", async () => {
      if (!selectedMiscRevision || miscFlashInFlight) return;
      miscFlashInFlight = true;
      $("btnMiscFlashConfirm").disabled = true;
      if ($("btnMiscDismiss")) $("btnMiscDismiss").disabled = true;
      const fb = $("miscFlashBanner");
      if (fb) {
        fb.style.display = "block";
        fb.className = "status-banner warn";
        fb.textContent = `Прошивка misc для ревизии ${formatMiscRevLabel(selectedMiscRevision)}...`;
      }
      try {
        const r = await api("/api/misc/flash", "POST", {
          revision: selectedMiscRevision,
          confirm: true
        });
        if (fb) {
          fb.className = "status-banner ok";
          fb.textContent = r.rebooting
            ? `misc прошит (${formatMiscRevLabel(selectedMiscRevision)}), станция перезагружается (~35 сек)...`
            : `misc прошит: ${r.detail || "OK"}`;
        }
        showToast("misc прошит, станция перезагружается...");
      } catch (e) {
        miscFlashInFlight = false;
        $("btnMiscFlashConfirm").disabled = false;
        if ($("btnMiscDismiss")) $("btnMiscDismiss").disabled = false;
        if (fb) {
          fb.className = "status-banner err";
          fb.textContent = "Ошибка прошивки misc: " + (e?.message || e);
        }
        showToast("Ошибка прошивки misc: " + (e?.message || e), true);
      }
    });
  }

  const mediaReceiverRows = [
    ["airplay", "Airplay", "В AirPlay на устройстве в той же сети выберите «Яндекс станция Макс»."],
  ];
  let mediaReceiverTimer;
  let mediaReceiverRefreshing = false;
  let mediaReceiverAction = false;

  async function refreshMediaReceivers() {
    if (!$("mediaReceivers") || mediaReceiverRefreshing || mediaReceiverAction) return;
    clearTimeout(mediaReceiverTimer);
    mediaReceiverRefreshing = true;
    try {
      const data = await api("/api/music/receivers");
      const installing = mediaReceiverRows.some(([key]) => data[key]?.state === "installing");
      for (const [key, suffix, instructions] of mediaReceiverRows) {
        const item = data[key];
        const status = $("media" + suffix + "Status");
        const button = $("media" + suffix + "Toggle");
        const current = item?.state;
        const text = {
          missing: "Требуется установка",
          installing: "Скачивание и установка…",
          running: instructions,
          stopped: "Выключен",
          error: "Приёмник не отвечает",
        };
        status.textContent = item?.message || text[current] || "Статус недоступен";
        button.hidden = !item || current === "unsupported" || current === "installing";
        button.disabled = installing;
        const action = current === "running" ? "stop" : item?.installed ? "start" : "install";
        button.textContent = { install: "Установить", start: "Включить", stop: "Выключить" }[action];
        button.onclick = async () => {
          if (mediaReceiverAction) return;
          mediaReceiverAction = true;
          for (const [, name] of mediaReceiverRows) $("media" + name + "Toggle").disabled = true;
          try {
            await api("/api/music/receivers", "POST", { receiver: key, action });
          } catch (error) {
            showToast(error.message || "Не удалось переключить приёмник", true);
          } finally {
            mediaReceiverAction = false;
            refreshMediaReceivers();
          }
        };
      }
      if (installing) mediaReceiverTimer = setTimeout(refreshMediaReceivers, 1500);
    } catch (_) {
      for (const [, suffix] of mediaReceiverRows) {
        $("media" + suffix + "Status").textContent = "Статус недоступен";
        $("media" + suffix + "Toggle").hidden = true;
      }
      mediaReceiverTimer = setTimeout(refreshMediaReceivers, 5000);
    } finally {
      mediaReceiverRefreshing = false;
    }
  }

  let currentMusicProvider = "yandex";
  let providerChoice = 0;
  let providerWrites = Promise.resolve();
  const spotifyDeepLink = new URLSearchParams(location.search).get("music") === "spotify";
  let openSpotifyFromLink = spotifyDeepLink;

  function setSpotifyFrameVisibility() {
    const frame = $("musicSpotify");
    if (!frame || !frame.hasAttribute("src")) return;
    frame.contentWindow?.postMessage({
      type: "openstation-spotify-visibility",
      active: currentMusicProvider === "spotify" && $("tab-music").classList.contains("active"),
    }, location.origin);
  }

  function renderMusicProvider(provider) {
    currentMusicProvider = provider === "spotify" ? "spotify" : "yandex";
    const spotify = currentMusicProvider === "spotify";
    $("tab-music").classList.toggle("spotify-selected", spotify);
    $("musicYandexLogin").hidden = spotify;
    const frame = $("musicSpotify");
    frame.hidden = !spotify;
    if (spotify && !frame.hasAttribute("src")) frame.src = "/spotify.html";
    $("musicProvider").querySelectorAll("[data-provider]").forEach((btn) => {
      const active = btn.dataset.provider === currentMusicProvider;
      btn.classList.toggle("active", active);
      btn.setAttribute("aria-pressed", String(active));
    });
    setSpotifyFrameVisibility();
  }

  async function syncMusicProvider() {
    if (openSpotifyFromLink) {
      openSpotifyFromLink = false;
      renderMusicProvider("spotify");
      return;
    }
    const choice = providerChoice;
    try {
      await providerWrites;
      const data = await api("/api/music/provider");
      if (choice === providerChoice) renderMusicProvider(data.provider);
    } catch (_) {}
  }

  $("musicProvider").addEventListener("click", (event) => {
    const button = event.target.closest("[data-provider]");
    if (!button) return;
    const provider = button.dataset.provider;
    const choice = ++providerChoice;
    renderMusicProvider(provider);
    providerWrites = providerWrites.then(async () => {
      try { await api("/api/music/provider", "POST", { provider }); }
      catch (error) {
        if (choice === providerChoice) showToast(error.message, true);
      }
    });
  });
  $("musicSpotify").addEventListener("load", setSpotifyFrameVisibility);
  window.addEventListener("message", (event) => {
    const frame = $("musicSpotify");
    if (event.origin !== location.origin || event.source !== frame.contentWindow ||
        event.data?.type !== "openstation-spotify-height") return;
    const height = Number(event.data.height);
    if (Number.isFinite(height)) frame.style.height = Math.max(280, Math.min(height, 14000)) + "px";
  });

  async function init() {
    let savedTab = null;
    try { savedTab = localStorage.getItem("os_tab"); } catch {}
    switchTab(openSpotifyFromLink ? "music" : (savedTab || "music"));
    try {
      const s = await api("/api/status");
      setConn(true);
      applyTelemetry(s, true);
      const hs = parseWizardHashStep();
      if (hs || s.postOtaWizardPending || (s.config?.setupCompleted === false && !sessionStorage.getItem("os_wiz_dismissed"))) {
        openWizard(hs || 1);
      }
      loadApps();
    } catch {
      setConn(false);
      showToast("Нет связи со станцией, переподключение...", true);
    }
    connectWs();
  }

  init();
})();

/* ===================== PLUGIN MARKETPLACE CONTROLLER ===================== */
(() => {
  const $ = (id) => document.getElementById(id);
  const tabEl = $("tab-plugins");
  if (!tabEl) return;

  let dataState = null;
  let activeCat = "all";
  let searchQuery = "";
  let currentModalPluginId = "";

  const esc = (s) => String(s ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");

  async function reqJson(url, method = "GET", body = null) {
    const opts = { method, headers: {} };
    if (body !== null) {
      opts.headers["Content-Type"] = "application/json";
      opts.body = JSON.stringify(body);
    }
    const r = await fetch(url, opts);
    let j = null;
    try { j = await r.json(); } catch {}
    if (!r.ok || (j && j.ok === false)) {
      throw new Error((j && (j.error || j.message)) || ("HTTP " + r.status));
    }
    return j || {};
  }

  function toast(msg, isErr = false) {
    const t = $("toast");
    if (!t) return;
    t.textContent = msg;
    t.className = "toast show" + (isErr ? " err" : "");
    setTimeout(() => t.classList.remove("show"), 2600);
  }

  function matchesFilter(item, isInstalledSection) {
    if (activeCat === "installed" && !item.installed) return false;
    if (activeCat !== "all" && activeCat !== "installed" && (item.category || "Утилиты") !== activeCat) return false;
    if (searchQuery) {
      const hay = [item.id, item.name, item.author, item.category, item.description, ...(item.tools || [])]
        .join(" ").toLowerCase();
      if (!hay.includes(searchQuery)) return false;
    }
    if (isInstalledSection && !item.installed) return false;
    return true;
  }

  function buildCard(item, installedMap) {
    const inst = item.installed ? (installedMap.get(item.id) || item) : null;
    const enabled = Boolean(inst && inst.enabled);
    const card = document.createElement("div");
    card.className = "pl-card" + (enabled ? " enabled" : "");

    const tools = (inst?.tools || item.tools || []).slice(0, 3);
    const verLabel = inst
      ? ("v" + (inst.version || item.version || "1.0.0") + (item.updateAvailable ? (" → v" + item.version) : ""))
      : ("v" + (item.version || "1.0.0"));

    card.innerHTML = `
      <div>
        <div class="pl-card-head">
          <div class="pl-card-id">
            <div class="pl-ico">${esc(item.icon || "🧩")}</div>
            <div style="min-width:0;">
              <div class="pl-name">${esc(item.name || item.id)}</div>
              <div class="pl-meta">${esc(item.author || "Community")} · ${esc(item.category || "Утилиты")} · ${esc(verLabel)}</div>
            </div>
          </div>
          ${inst ? `
            <label class="pl-switch" title="${enabled ? "Выключить" : "Включить"}">
              <input type="checkbox" class="js-pl-toggle" ${enabled ? "checked" : ""} />
              <span class="pl-slider"></span>
            </label>
          ` : ""}
        </div>
        <p class="pl-desc" style="margin-top:10px;">${esc(item.description || "")}</p>
        <div class="pl-tags" style="margin-top:10px;">
          ${inst ? `<span class="pl-tag">${enabled ? "Включён" : "Выключен"}</span>` : `<span class="pl-tag">Доступен</span>`}
          ${tools.map((t) => `<span class="pl-tag tool">${esc(t)}</span>`).join("")}
          ${inst && inst.lastError ? `<span class="pl-tag err" title="${esc(inst.lastError)}">Ошибка</span>` : ""}
        </div>
      </div>
      <div class="pl-card-foot">
        <div class="btn-row" style="margin-top:0; gap:6px; width:100%; justify-content:flex-end;">
          ${!inst ? `<button class="btn btn-sm btn-accent js-pl-install" type="button">Установить</button>` : ""}
          ${inst && item.updateAvailable ? `<button class="btn btn-sm btn-accent js-pl-update" type="button">Обновить</button>` : ""}
          ${inst ? `<button class="btn btn-sm js-pl-open" type="button">⚙ Настроить / Запуск</button>` : ""}
        </div>
      </div>
    `;

    card.querySelector(".js-pl-install")?.addEventListener("click", async (e) => {
      e.target.disabled = true;
      try {
        await reqJson("/api/plugins/install", "POST", { id: item.id });
        toast("Плагин «" + (item.name || item.id) + "» установлен и включён");
        await refresh(false);
      } catch (err) {
        toast(err.message, true);
      } finally {
        e.target.disabled = false;
      }
    });

    card.querySelector(".js-pl-update")?.addEventListener("click", async (e) => {
      e.target.disabled = true;
      try {
        await reqJson("/api/plugins/install", "POST", { id: item.id });
        toast("Плагин обновлён");
        await refresh(false);
      } catch (err) {
        toast(err.message, true);
      } finally {
        e.target.disabled = false;
      }
    });

    card.querySelector(".js-pl-toggle")?.addEventListener("change", async (e) => {
      const nextState = e.target.checked;
      e.target.disabled = true;
      try {
        await reqJson("/api/plugins/toggle", "POST", { id: item.id, enabled: nextState });
        toast((item.name || item.id) + (nextState ? ": включён" : ": выключен"));
        await refresh(false);
      } catch (err) {
        e.target.checked = !nextState;
        toast(err.message, true);
      } finally {
        e.target.disabled = false;
      }
    });

    card.querySelector(".js-pl-open")?.addEventListener("click", () => openDetailModal(inst));
    return card;
  }

  function render() {
    if (!dataState) return;
    const installedList = Array.isArray(dataState.installed) ? dataState.installed : [];
    const catalogList = Array.isArray(dataState.catalog) ? dataState.catalog : [];
    const installedMap = new Map(installedList.map((p) => [p.id, p]));

    if ($("plStatsLine")) {
      $("plStatsLine").textContent =
        `Установлено: ${dataState.installedCount || 0} · Включено: ${dataState.enabledCount || 0} · В каталоге: ${dataState.catalogCount || 0}`;
    }

    const banner = $("pluginCatalogBanner");
    if (banner) {
      if (dataState.catalogError) {
        banner.style.display = "block";
        banner.className = "status-banner warn";
        banner.textContent = "Каталог GitHub временно недоступен (" + dataState.catalogError + "). Используйте локальный кэш или установку по URL / JSON.";
      } else {
        banner.style.display = "none";
      }
    }

    const instSec = $("pluginInstalledSection");
    const instGrid = $("pluginInstalledGrid");
    if (instSec && instGrid) {
      instGrid.replaceChildren();
      const filteredInst = installedList.filter((p) => matchesFilter(p, true));
      instSec.style.display = filteredInst.length ? "block" : "none";
      filteredInst.forEach((p) => instGrid.appendChild(buildCard(p, installedMap)));
    }

    const catGrid = $("pluginCatalogGrid");
    if (catGrid) {
      catGrid.replaceChildren();
      const filteredCat = catalogList.filter((p) => matchesFilter(p, false));
      if (!filteredCat.length) {
        const empty = document.createElement("div");
        empty.className = "card";
        empty.innerHTML = `<p class="hint" style="margin:0;">Нет плагинов по выбранному фильтру. Нажмите «↻ Обновить каталог» или установите плагин по URL / JSON.</p>`;
        catGrid.appendChild(empty);
      } else {
        filteredCat.forEach((p) => catGrid.appendChild(buildCard(p, installedMap)));
      }
    }

    if (currentModalPluginId && $("pluginDetailModal")?.style.display === "flex") {
      const updated = installedMap.get(currentModalPluginId);
      if (updated) bindDetailModal(updated);
    }
  }

  function bindDetailModal(pl) {
    currentModalPluginId = pl.id;
    $("plModalTitle").textContent = (pl.icon ? pl.icon + " " : "") + (pl.name || pl.id);
    $("plModalBadge").textContent = "v" + (pl.version || "1.0.0");
    $("plModalSub").textContent = `ID: ${pl.id} · Автор: ${pl.author || "Community"} · Категория: ${pl.category || "Утилиты"}`;
    $("plModalDesc").textContent = pl.description || "";
    $("plModalEnabledSwitch").checked = Boolean(pl.enabled);

    const toolsRow = $("plModalToolsRow");
    toolsRow.replaceChildren();
    (pl.tools || []).forEach((t) => {
      const span = document.createElement("span");
      span.className = "pl-tag tool";
      span.textContent = "Голос: " + t;
      toolsRow.appendChild(span);
    });

    const settingsBox = $("plModalSettingsBox");
    settingsBox.replaceChildren();
    const schema = Array.isArray(pl.settingsSchema) ? pl.settingsSchema : [];
    const curSettings = pl.settings || {};
    $("btnPlSaveSettings").style.display = schema.length ? "inline-flex" : "none";
    if (schema.length) {
      const h = document.createElement("h4");
      h.style.margin = "0 0 8px";
      h.textContent = "Настройки";
      settingsBox.appendChild(h);
      schema.forEach((f) => {
        const row = document.createElement("div");
        row.className = f.type === "bool" ? "form-row toggle-row" : "form-row";
        const lbl = document.createElement("label");
        lbl.textContent = f.label || f.key;
        row.appendChild(lbl);

        const val = curSettings[f.key] ?? f.default ?? "";
        let inp;
        if (f.type === "select" && Array.isArray(f.options)) {
          inp = document.createElement("select");
          f.options.forEach((opt) => {
            const o = document.createElement("option");
            o.value = opt;
            o.textContent = opt;
            if (String(val) === String(opt)) o.selected = true;
            inp.appendChild(o);
          });
        } else if (f.type === "bool") {
          inp = document.createElement("input");
          inp.type = "checkbox";
          inp.checked = Boolean(val);
        } else {
          inp = document.createElement("input");
          inp.type = f.type === "secret" ? "password" : (f.type === "number" ? "number" : "text");
          inp.value = f.type === "secret" ? "" : String(val);
          inp.placeholder = f.type === "secret" && val ? "Сохранён (оставьте пустым чтобы не менять)" : (f.placeholder || "");
        }
        inp.dataset.plSettingKey = f.key;
        inp.dataset.plSettingType = f.type || "string";
        row.appendChild(inp);
        if (f.help) {
          const hp = document.createElement("div");
          hp.className = "hint";
          hp.textContent = f.help;
          row.appendChild(hp);
        }
        settingsBox.appendChild(row);
      });
    }

    const actBox = $("plModalActionsBox");
    actBox.replaceChildren();
    const actions = Array.isArray(pl.actions) ? pl.actions : [];
    if (actions.length) {
      const h = document.createElement("h4");
      h.style.margin = "0 0 8px";
      h.textContent = "Действия и проверка";
      actBox.appendChild(h);
      actions.forEach((a) => {
        const wrap = document.createElement("div");
        wrap.className = "btn-row";
        wrap.style.marginTop = "6px";
        const argInputs = [];
        (a.inputs || []).forEach((ai) => {
          const inp = document.createElement("input");
          inp.type = "text";
          inp.placeholder = ai.label || ai.key;
          inp.value = ai.default || "";
          inp.style.flex = "1 1 160px";
          inp.dataset.actArg = ai.key;
          wrap.appendChild(inp);
          argInputs.push(inp);
        });
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "btn btn-sm btn-accent";
        btn.textContent = a.label || a.id;
        btn.addEventListener("click", async () => {
          btn.disabled = true;
          const resEl = $("plModalResult");
          resEl.style.display = "block";
          resEl.textContent = "Выполняю...";
          try {
            const args = {};
            argInputs.forEach((el) => { args[el.dataset.actArg] = el.value; });
            const out = await reqJson("/api/plugins/run", "POST", { id: pl.id, actionId: a.id, args });
            resEl.textContent = JSON.stringify(out, null, 2);
          } catch (err) {
            resEl.textContent = "Ошибка: " + err.message;
          } finally {
            btn.disabled = false;
          }
        });
        wrap.appendChild(btn);
        actBox.appendChild(wrap);
      });
    }
  }

  function openDetailModal(pl) {
    if (!pl) return;
    $("plModalResult").style.display = "none";
    $("plModalResult").textContent = "";
    bindDetailModal(pl);
    $("pluginDetailModal").style.display = "flex";
  }

  async function refresh(force = false) {
    try {
      dataState = await reqJson("/api/plugins" + (force ? "?refresh=1" : ""));
      render();
    } catch (err) {
      toast("Ошибка загрузки плагинов: " + err.message, true);
    }
  }

  $("btnPluginsRefresh")?.addEventListener("click", async () => {
    await refresh(true);
    toast("Каталог плагинов обновлён");
  });

  $("pluginSearchInput")?.addEventListener("input", (e) => {
    searchQuery = e.target.value.trim().toLowerCase();
    render();
  });

  $("pluginFilterChips")?.addEventListener("click", (e) => {
    const btn = e.target.closest("[data-plcat]");
    if (!btn) return;
    activeCat = btn.dataset.plcat;
    $("pluginFilterChips").querySelectorAll(".seg").forEach((b) => b.classList.toggle("active", b === btn));
    render();
  });

  $("btnPlDetailClose")?.addEventListener("click", () => { $("pluginDetailModal").style.display = "none"; });
  $("btnPlDetailCloseFoot")?.addEventListener("click", () => { $("pluginDetailModal").style.display = "none"; });

  $("plModalEnabledSwitch")?.addEventListener("change", async (e) => {
    if (!currentModalPluginId) return;
    const nextState = e.target.checked;
    try {
      await reqJson("/api/plugins/toggle", "POST", { id: currentModalPluginId, enabled: nextState });
      toast(nextState ? "Плагин включён" : "Плагин выключен");
      await refresh(false);
    } catch (err) {
      e.target.checked = !nextState;
      toast(err.message, true);
    }
  });

  $("btnPlSaveSettings")?.addEventListener("click", async () => {
    if (!currentModalPluginId) return;
    const settings = {};
    $("plModalSettingsBox").querySelectorAll("[data-pl-setting-key]").forEach((el) => {
      const k = el.dataset.plSettingKey;
      const t = el.dataset.plSettingType;
      if (t === "bool") settings[k] = el.checked;
      else if (t === "number") settings[k] = Number(el.value || 0);
      else settings[k] = el.value;
    });
    try {
      await reqJson("/api/plugins/settings", "POST", { id: currentModalPluginId, settings });
      toast("Настройки плагина сохранены");
      await refresh(false);
    } catch (err) {
      toast(err.message, true);
    }
  });

  $("btnPlUninstall")?.addEventListener("click", async () => {
    if (!currentModalPluginId) return;
    if (!confirm("Удалить плагин " + currentModalPluginId + "?")) return;
    try {
      await reqJson("/api/plugins/uninstall", "POST", { id: currentModalPluginId });
      $("pluginDetailModal").style.display = "none";
      toast("Плагин удалён");
      await refresh(false);
    } catch (err) {
      toast(err.message, true);
    }
  });

  function openPublishModal(mode = "install") {
    $("plPubBanner").style.display = "none";
    $("pluginPublishModal").style.display = "flex";
    const isPub = mode === "publish";
    $("plPubInstallPane").style.display = isPub ? "none" : "block";
    $("plPubCreatePane").style.display = isPub ? "block" : "none";
    $("plPubModeTabs").querySelectorAll(".seg").forEach((b) => b.classList.toggle("active", b.dataset.plpub === mode));
  }

  $("btnPluginCustomOpen")?.addEventListener("click", () => openPublishModal("install"));
  $("btnPluginSubmitOpen")?.addEventListener("click", () => openPublishModal("publish"));
  $("btnPlPubClose")?.addEventListener("click", () => { $("pluginPublishModal").style.display = "none"; });
  $("btnPlPubCloseFoot")?.addEventListener("click", () => { $("pluginPublishModal").style.display = "none"; });
  $("plPubModeTabs")?.addEventListener("click", (e) => {
    const b = e.target.closest("[data-plpub]");
    if (b) openPublishModal(b.dataset.plpub);
  });

  $("btnPlInstallUrl")?.addEventListener("click", async () => {
    const url = ($("plCustomUrlInput")?.value || "").trim();
    if (!url) return toast("Введите URL файла plugin.json", true);
    try {
      const r = await reqJson("/api/plugins/install", "POST", { url });
      toast("Установлен плагин: " + (r.id || url));
      $("pluginPublishModal").style.display = "none";
      await refresh(false);
    } catch (err) {
      toast(err.message, true);
    }
  });

  $("btnPlInstallJson")?.addEventListener("click", async () => {
    const raw = ($("plCustomJsonInput")?.value || "").trim();
    if (!raw) return toast("Вставьте JSON манифест", true);
    let manifest;
    try { manifest = JSON.parse(raw); } catch { return toast("Некорректный JSON", true); }
    try {
      const r = await reqJson("/api/plugins/install", "POST", { manifest });
      toast("Установлен плагин: " + (r.id || "ok"));
      $("pluginPublishModal").style.display = "none";
      await refresh(false);
    } catch (err) {
      toast(err.message, true);
    }
  });

  function buildGeneratedManifest() {
    const id = ($("plGenId")?.value || "my-plugin").trim().toLowerCase().replace(/[^a-z0-9_-]/g, "-");
    const name = ($("plGenName")?.value || "Мой плагин").trim();
    const author = ($("plGenAuthor")?.value || "Community").trim();
    const category = $("plGenCat")?.value || "Утилиты";
    const tool = ($("plGenTool")?.value || "plugin_custom_hook").trim().toLowerCase().replace(/[^a-z0-9_]/g, "_");
    const url = ($("plGenUrl")?.value || "https://httpbin.org/get?q={{args.query}}").trim();
    const desc = ($("plGenDesc")?.value || "Пользовательский плагин для OpenStation Max").trim();
    return {
      schemaVersion: 1,
      id,
      name,
      version: "1.0.0",
      author,
      category,
      icon: "🧩",
      description: desc,
      homepage: "https://github.com/Olegu621/OPENSTATION/tree/main/plugins/" + id,
      tools: [
        {
          name: tool,
          description: desc,
          parameters: { query: { type: "STRING", description: "Запрос или параметр" } },
          required: [],
          runner: { type: "http", method: "GET", url, timeoutMs: 7000 }
        }
      ],
      actions: [
        { id: "run", label: "Проверить", runner: { type: "http", method: "GET", url, timeoutMs: 7000 } }
      ]
    };
  }

  $("btnPlGenBuild")?.addEventListener("click", () => {
    const txt = JSON.stringify(buildGeneratedManifest(), null, 2);
    $("plGenOutput").value = txt;
    const gh = $("btnPlGenGithubIssue");
    if (gh) {
      const mf = buildGeneratedManifest();
      const body = "### Данные плагина\n- **ID:** `" + mf.id + "`\n- **Категория:** " + mf.category +
        "\n- **Описание:** " + mf.description + "\n\n```json\n" + txt + "\n```\n";
      gh.href = "https://github.com/Olegu621/OPENSTATION/issues/new?title=" +
        encodeURIComponent("[Plugin] " + mf.name) + "&body=" + encodeURIComponent(body);
    }
  });

  $("btnPlGenTestInstall")?.addEventListener("click", async () => {
    const mf = buildGeneratedManifest();
    $("plGenOutput").value = JSON.stringify(mf, null, 2);
    try {
      await reqJson("/api/plugins/install", "POST", { manifest: mf });
      toast("Плагин «" + mf.name + "» установлен локально для теста");
      await refresh(false);
    } catch (err) {
      toast(err.message, true);
    }
  });

  $("btnPlGenDownload")?.addEventListener("click", () => {
    const txt = $("plGenOutput")?.value || JSON.stringify(buildGeneratedManifest(), null, 2);
    $("plGenOutput").value = txt;
    const blob = new Blob([txt + "\n"], { type: "application/json" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = "plugin.json";
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  });

  window.osPlugins = { refresh };
  if (tabEl.classList.contains("active")) refresh(true);
})();
