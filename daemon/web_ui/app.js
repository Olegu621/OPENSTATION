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
  let wizPopulatedOnce = false;
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
    document.querySelectorAll(".tab").forEach((b) => {
      b.classList.toggle("active", b.dataset.tab === tabName);
    });
    document.querySelectorAll(".panel").forEach((p) => {
      p.classList.toggle("active", p.id === "tab-" + tabName);
    });
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
        visualizerStyle: $("visualizerStyle")?.value || "dots",
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
      if ($("actShort")) inp.actionShort = $("actShort").value;
      if ($("actLong")) inp.actionLong = $("actLong").value;
      payload.input = inp;
    }

    if ($("astEnabled")) {
      const ast = {
        enabled: $("astEnabled").checked,
        voiceName: $("astVoice")?.value || "Aoede",
        systemPrompt: $("astPrompt")?.value || "",
        wakeWordEnabled: $("astWakeEn") ? $("astWakeEn").checked : true,
        chimeEnabled: $("astChime") ? $("astChime").checked : true
      };
      const newGeminiKey = $("astApiKey")?.value.trim() || "";
      if (newGeminiKey) ast.geminiApiKey = newGeminiKey;
      if ($("astMode")) ast.mode = $("astMode").value;
      if ($("astModel")) ast.geminiModel = $("astModel").value.trim() || "gemini-3.8-live";
      if ($("astProxyHost")) ast.geminiProxyHost = $("astProxyHost").value.trim();
      if ($("astFollowUp")) ast.followUpSec = Number.isFinite(parseInt($("astFollowUp").value, 10)) ? parseInt($("astFollowUp").value, 10) : 8;
      if ($("astMaxSess")) ast.maxSessionSec = parseInt($("astMaxSess").value, 10) || 120;
      if ($("astEchoGuard")) ast.echoGuard = $("astEchoGuard").checked;
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
        pi.innerHTML = '<path fill="white" d="M144 479H48c-26.5 0-48-21.5-48-48V79c0-26.5 21.5-48 48-48h96c26.5 0 48 21.5 48 48v352c0 26.5-21.5 48-48 48zm304-48V79c0-26.5 21.5-48 48-48h-96c-26.5 0-48 21.5-48 48v352c0 26.5 21.5 48 48 48h96c26.5 0 48-21.5 48-48z"/>';
      } else {
        pi.innerHTML = '<path fill="white" d="M424.4 214.7L72.4 6.6C43.8-10.3 0 6.1 0 47.9V464c0 37.5 40.7 60.1 72.4 41.3l352-208c31.4-18.5 31.5-64.1 0-82.6z" />';
      }
    }
    const lk = $("btnMusicLike");
    if (lk) {
      lk.classList.toggle("liked", !!m.liked);
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

  function applyAliceBanner(astState, ast, cfgAst) {
    const stRu = ALICE_STATE_RU[astState] || astState;
    const qb = $("btnQuickAlice");
    if (qb) {
      qb.textContent = `Алиса: ${stRu}`;
      qb.classList.toggle("active", !!(ast?.active));
    }
    const ww = ast?.wakeWord || {};
    const bww = $("badgeWakeWord");
    if (bww) {
      bww.textContent = ww.ready ? "Голосовой запуск: ВКЛ" : "Голосовой запуск: выкл";
      bww.className = "badge " + (ww.ready ? "ok" : "warn");
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
        ban.textContent = `Алиса: ${stRu}`;
        ban.className = "status-banner live";
      } else {
        ban.textContent = ww.ready
          ? "Готова — скажите «Алиса» или нажмите кнопку ниже"
          : "Готова к запуску кнопкой (отклик на фразу «Алиса» выключен)";
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
        $("btnQuickBt").textContent = `ᛒ BT: Виден (${rem}с)`;
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
    const backendName = eng.backend || "aaudio";
    const vadActive = !!(ast.wakeWord?.vadSpeech);

    if ($("badgeMicDsp")) {
      $("badgeMicDsp").textContent = backendName.includes("vqe") ? "Yandex VQE DSP · 16 кГц" : backendName;
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
    applyBootanimState(d.bootanim || {});
    applyOtaState(d.ota || {}, d.firmwareVersion);
    applyWifiAdbState(d.wifiAdb || {}, d);

    if (otaPendingRestart && !d.ota?.busy && d.ota?.state !== "restarting") {
      otaPendingRestart = false;
      showToast("Прошивка OpenStation Max v" + (d.firmwareVersion || "1.5.0") + " активна!");
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
      if ($("visualizerStyle") && cfg.ledScreen?.visualizerStyle) $("visualizerStyle").value = cfg.ledScreen.visualizerStyle;
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

      if ($("locCity")) $("locCity").value = cfg.location?.city || "";

      if ($("actShort")) $("actShort").value = cfg.input?.actionShort || "assistant";
      if ($("actLong")) $("actLong").value = cfg.input?.actionLong || "led_cycle";
      if ($("rotaryInvert")) $("rotaryInvert").checked = !!cfg.input?.rotaryInvert;
      if ($("rotaryStep")) $("rotaryStep").value = cfg.input?.rotaryStep ?? 1;
      if ($("devHost")) $("devHost").value = cfg.deviceHostname || "station-max";

      if (cfg.assistant) {
        if ($("astEnabled")) $("astEnabled").checked = !!cfg.assistant.enabled;
        if ($("astMode")) $("astMode").value = cfg.assistant.mode || "gemini";
        if ($("astApiKey")) {
          $("astApiKey").value = "";
          $("astApiKey").placeholder = cfg.assistant.geminiApiKeySet ? "Ключ уже сохранён; пустое поле оставляет его без изменений" : "Вставьте Gemini API key";
        }
        if ($("astModel")) $("astModel").value = cfg.assistant.geminiModel || "gemini-3.8-live";
        if ($("astProxyHost")) {
          $("astProxyHost").value =
            cfg.assistant.geminiProxyHost !== undefined ? cfg.assistant.geminiProxyHost : "ai-pool.comss.one";
        }
        if ($("astVoice")) $("astVoice").value = cfg.assistant.voiceName || "Aoede";
        if ($("astPrompt")) $("astPrompt").value = cfg.assistant.systemPrompt || "";
        if ($("astWakeEn")) $("astWakeEn").checked = cfg.assistant.wakeWordEnabled !== false;
        if ($("astChime")) $("astChime").checked = cfg.assistant.chimeEnabled !== false;
        if ($("astSearch")) $("astSearch").checked = cfg.assistant.googleSearch === true;
        if ($("astEchoGuard")) $("astEchoGuard").checked = cfg.assistant.echoGuard !== false;
        if ($("astAgentMode")) $("astAgentMode").checked = cfg.assistant.agentMode === true;
        if ($("astFollowUp")) $("astFollowUp").value = cfg.assistant.followUpSec ?? 8;
        if ($("astMaxSess")) $("astMaxSess").value = cfg.assistant.maxSessionSec ?? 120;
        if (Array.isArray(cfg.assistant.memoryNotes)) {
          currentMemoryNotes = cfg.assistant.memoryNotes.slice();
          renderMemoryNotes();
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
          if (res.city && $("locCity")) {
            $("locCity").value = res.city;
            scheduleConfigPush();
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
          if (res && res.valid) {
            const desc = `🌡 ${res.temperature}°C, ${res.conditionText || "Ясно"} (${res.city})`;
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
    "eqPreset", "actShort", "actLong", "rotaryInvert", "rotaryStep", "locCity",
    "wyoEn", "wyoAlways", "astEnabled", "astMode", "astVoice", "astModel",
    "astPrompt", "astWakeEn", "astChime", "astSearch", "astEchoGuard",
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
          throw new Error((data && data.message) || `HTTP ${res.status}`);
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
    standby: "Выключение ТВ",
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
  async function triggerPairRemote() {
    try {
      const r = await api("/api/remote/pair", "POST", {});
      showToast(r.ok !== false ? "Запущен автопоиск штатного BLE-пульта YNDX..." : (r.error || "Не удалось запустить поиск"), r.ok === false);
    } catch (e) { showToast("Ошибка BLE-поиска: " + e.message, true); }
  }
  async function triggerFindRemote() {
    try {
      const r = await api("/api/remote/find", "POST", {});
      showToast(r.ok !== false ? "Отправлен звуковой сигнал поиска на BLE-пульт" : (r.error || "Пульт не подключён"), r.ok === false);
    } catch (e) { showToast("Ошибка поиска пульта: " + e.message, true); }
  }
  if ($("btnPairBleRemote")) $("btnPairBleRemote").addEventListener("click", triggerPairRemote);
  if ($("btnWizPairBle")) $("btnWizPairBle").addEventListener("click", triggerPairRemote);
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
      if (!wsReconnectTimer) {
        wsReconnectTimer = setTimeout(connectWs, 2000);
      }
    };
    ws.onmessage = (ev) => {
      if (typeof ev.data === "string") {
        try {
          const msg = JSON.parse(ev.data);
          if (msg.type === "telemetry") applyTelemetry(msg.data, state === null);
          else if (msg.type === "ota") {
            if (state) state.ota = msg.data;
            applyOtaState(msg.data, state?.firmwareVersion);
            if (msg.data?.state === "up_to_date" && msg.data?.progressPct === 100 && !msg.data?.updateAvailable && otaPendingRestart) {
              otaPendingRestart = false;
              showToast("Прошивка обновлена до v" + (msg.data.installedVersion || "1.5.0"));
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
        geminiProxyHost: proxyVal ?? "ai-pool.comss.one"
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

  function setWizardStep(step) {
    wizStep = Math.max(1, Math.min(5, step));
    document.querySelectorAll(".wiz-step-pill").forEach((b) => {
      b.classList.toggle("active", parseInt(b.dataset.step, 10) === wizStep);
    });
    for (let i = 1; i <= 5; i++) {
      const p = $("wizStep" + i);
      if (p) p.classList.toggle("active", i === wizStep);
    }
    if ($("btnWizPrev")) $("btnWizPrev").style.visibility = wizStep > 1 ? "visible" : "hidden";
    if ($("btnWizNext")) $("btnWizNext").style.display = wizStep < 5 ? "inline-flex" : "none";
    if ($("btnWizFinish")) $("btnWizFinish").style.display = wizStep === 5 ? "inline-flex" : "none";
    if ($("wizProgressText")) $("wizProgressText").textContent = `Шаг ${wizStep} из 5`;
    if (state) syncWizardTelemetry(state, false);
  }

  function openWizard(step = 1) {
    wizPopulatedOnce = false;
    if (state) syncWizardTelemetry(state, true);
    setWizardStep(step);
    if ($("wizardModal")) $("wizardModal").style.display = "flex";
  }

  function closeWizard(rememberDismiss = true) {
    if ($("wizardModal")) $("wizardModal").style.display = "none";
    if (rememberDismiss) sessionStorage.setItem("os_wiz_dismissed", "1");
    if (location.hash === "#wizard") {
      try { history.replaceState(null, "", location.pathname + location.search); } catch {}
    }
  }

  function syncWizardTelemetry(d, populateFields) {
    const hw = d.hardware || {};
    const cfg = d.config || {};
    const ast = d.assistant || {};
    const ww = ast.wakeWord || {};
    const m = d.music || {};

    if ($("wizFwBadge")) {
      $("wizFwBadge").textContent = `v${d.firmwareVersion || "1.5.0"}`;
    }
    if ($("sysAutoInfo")) {
      $("sysAutoInfo").textContent = `Версия v${d.firmwareVersion || "1.5.0"} · Все службы работают автономно внутри станции (${d.ip || "local"}).`;
    }
    if ($("wizDiagIp")) $("wizDiagIp").textContent = d.ip || "127.0.0.1";
    if ($("wizDiagMdns")) $("wizDiagMdns").textContent = `${d.hostname || "station-max"}.local`;
    if ($("wizDiagHw")) {
      $("wizDiagHw").textContent = hw.fb5Present ? "Активен" : "Отключён";
    }
    if ($("wizDiagMic")) {
      $("wizDiagMic").textContent = ww.ready ? "Фраза «Алиса» активна" : (ww.status || "Выкл");
    }

    const dnsHost = d.privateDnsHost || "";
    const dnsMode = d.privateDnsMode || "";
    if ($("sysDnsHost") && document.activeElement !== $("sysDnsHost") && dnsHost) {
      $("sysDnsHost").value = dnsHost;
    }
    if ($("wizDnsHost") && document.activeElement !== $("wizDnsHost") && dnsHost) {
      $("wizDnsHost").value = dnsHost;
    }
    if ($("wizNetBanner")) {
      const dnsLabel = dnsMode === "hostname" && dnsHost ? `DNS: ${dnsHost}` : "DNS: авто";
      $("wizNetBanner").textContent = `✓ Автономный режим · IP: ${d.ip || "local"} · ${dnsLabel}`;
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

    if ($("wizChecklist")) {
      const hasKey = !!(cfg.assistant?.geminiApiKeySet || cfg.assistant?.geminiApiKey);
      const checks = [
        { ok: true, txt: "Станция в сети и готова" },
        { ok: hasKey, txt: hasKey ? "Ключ Алисы (Gemini) задан" : "Ключ Алисы не задан (Шаг 2)" },
        { ok: !!ww.ready, txt: ww.ready ? "Отклик на фразу «Алиса» активен" : "Отклик на фразу выключен" },
        { ok: !!m.hasAuth, txt: m.hasAuth ? `Яндекс Музыка: @${m.userLogin || "OK"}` : "Яндекс Музыка: без входа (опц.)" }
      ];
      $("wizChecklist").innerHTML = checks.map((c) =>
        `<div class="wiz-check-item ${c.ok ? "ok" : "warn"}">${c.ok ? "✓" : "•"} ${c.txt}</div>`
      ).join("");
    }

    if ((populateFields || !wizPopulatedOnce) && cfg.assistant) {
      wizPopulatedOnce = true;
      if ($("wizApiKey") && document.activeElement !== $("wizApiKey")) $("wizApiKey").value = cfg.assistant.geminiApiKey || "";
      if ($("wizProxyHost") && document.activeElement !== $("wizProxyHost")) {
        $("wizProxyHost").value = cfg.assistant.geminiProxyHost !== undefined ? cfg.assistant.geminiProxyHost : "ai-pool.comss.one";
      }
      if ($("wizVoice") && document.activeElement !== $("wizVoice")) $("wizVoice").value = cfg.assistant.voiceName || "Aoede";
      if ($("wizWakeEn")) $("wizWakeEn").checked = cfg.assistant.wakeWordEnabled !== false;
      if ($("wizAudioRoute") && document.activeElement !== $("wizAudioRoute")) $("wizAudioRoute").value = cfg.audio?.outputRoute || "auto";
      if ($("wizEqPreset") && document.activeElement !== $("wizEqPreset")) $("wizEqPreset").value = cfg.audio?.eqPreset || "balanced";
      if ($("wizRingMode") && document.activeElement !== $("wizRingMode")) $("wizRingMode").value = cfg.ledRing?.mode || "music";
      if ($("wizRingColor") && document.activeElement !== $("wizRingColor")) $("wizRingColor").value = cfg.ledRing?.color || "#00d4ff";
      if ($("wizAutoBri")) $("wizAutoBri").checked = cfg.ledRing?.autoBrightness !== false;
      if ($("wizActShort") && document.activeElement !== $("wizActShort")) $("wizActShort").value = cfg.input?.actionShort || "assistant";
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
      runGeminiLiveTest($("astApiKey")?.value.trim(), $("astModel")?.value.trim(), $("astProxyHost")?.value.trim(), $("aliceTestResult"));
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
  if ($("btnApplyDns") && $("sysDnsHost")) $("btnApplyDns").addEventListener("click", () => applyDnsHost($("sysDnsHost").value));
  if ($("btnWizApplyDns") && $("wizDnsHost")) $("btnWizApplyDns").addEventListener("click", () => applyDnsHost($("wizDnsHost").value));

  if ($("btnWizTestGemini")) {
    $("btnWizTestGemini").addEventListener("click", async () => {
      const key = $("wizApiKey")?.value.trim() || "";
      const proxy = $("wizProxyHost")?.value.trim() ?? "ai-pool.comss.one";
      await pushPartialConfig({
        assistant: {
          enabled: true,
          mode: "gemini",
          geminiApiKey: key,
          geminiProxyHost: proxy,
          voiceName: $("wizVoice")?.value || "Aoede",
          wakeWordEnabled: $("wizWakeEn") ? $("wizWakeEn").checked : true
        }
      });
      await runGeminiLiveTest(key, $("astModel")?.value.trim() || "gemini-3.8-live", proxy, $("wizGeminiBanner"));
    });
  }
  if ($("btnWizTestSay")) $("btnWizTestSay").addEventListener("click", triggerAlice);

  ["wizVoice", "wizWakeEn"].forEach((id) => {
    if ($(id)) {
      $(id).addEventListener("change", () => pushPartialConfig({
        assistant: {
          voiceName: $("wizVoice")?.value || "Aoede",
          wakeWordEnabled: $("wizWakeEn") ? $("wizWakeEn").checked : true,
          geminiApiKey: $("wizApiKey")?.value.trim() || "",
          geminiProxyHost: $("wizProxyHost")?.value.trim() ?? "ai-pool.comss.one"
        }
      }, "Параметры Алисы сохранены"));
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
  if ($("btnWizFinish")) {
    $("btnWizFinish").addEventListener("click", async () => {
      await pushPartialConfig({
        assistant: {
          geminiApiKey: $("wizApiKey")?.value.trim() || "",
          geminiProxyHost: $("wizProxyHost")?.value.trim() ?? "ai-pool.comss.one",
          voiceName: $("wizVoice")?.value || "Aoede",
          wakeWordEnabled: $("wizWakeEn") ? $("wizWakeEn").checked : true
        },
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
    if (location.hash === "#wizard") openWizard(1);
  });

  async function init() {
    try {
      const s = await api("/api/status");
      applyTelemetry(s, true);
      if (location.hash === "#wizard" || (s.config?.setupCompleted === false && !sessionStorage.getItem("os_wiz_dismissed"))) {
        openWizard(1);
      }
      loadApps();
    } catch {
      showToast("Нет связи со станцией, переподключение...", true);
    }
    connectWs();
  }

  init();
})();
