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

  const COMPONENT_RU = {
    daemon: "Система",
    launcher: "ТВ-экран",
    ime: "Клавиатура",
    bootanimation: "Заставка"
  };

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
        brightness: parseInt($("scrBri")?.value ?? "100", 10)
      };
      if ($("scrAutoBri")) ls.autoBrightness = $("scrAutoBri").checked;
      if ($("scrSpeed")) ls.scrollSpeed = parseInt($("scrSpeed").value, 10);
      payload.ledScreen = ls;
    }

    if ($("eqPreset")) {
      payload.audio = {
        outputRoute: document.querySelector("#audioRoutes .seg.active")?.dataset.route || "auto",
        eqPreset: $("eqPreset").value
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
        geminiApiKey: $("astApiKey")?.value.trim() || "",
        systemPrompt: $("astPrompt")?.value || "",
        wakeWordEnabled: $("astWakeEn") ? $("astWakeEn").checked : true,
        chimeEnabled: $("astChime") ? $("astChime").checked : true
      };
      if ($("astMode")) ast.mode = $("astMode").value;
      if ($("astModel")) ast.geminiModel = $("astModel").value.trim() || "gemini-3.8-live";
      if ($("astProxyHost")) ast.geminiProxyHost = $("astProxyHost").value.trim();
      if ($("astFollowUp")) ast.followUpSec = Number.isFinite(parseInt($("astFollowUp").value, 10)) ? parseInt($("astFollowUp").value, 10) : 8;
      if ($("astMaxSess")) ast.maxSessionSec = parseInt($("astMaxSess").value, 10) || 120;
      if ($("astEchoGuard")) ast.echoGuard = $("astEchoGuard").checked;
      if ($("astSearch")) ast.googleSearch = $("astSearch").checked;
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
        password: $("mqttPass")?.value || "",
        baseTopic: $("mqttTopic")?.value.trim() || "openstation/station_max"
      };
    }

    if ($("otaAutoCheck") || $("otaSyncBootanim")) {
      const ota = {};
      if ($("otaAutoCheck")) ota.autoCheck = $("otaAutoCheck").checked;
      if ($("otaSyncBootanim")) ota.syncBootanimation = $("otaSyncBootanim").checked;
      if ($("otaRepo")) ota.repo = $("otaRepo").value.trim() || "Olegu621/OPENSTATION";
      if ($("otaBranch")) ota.branch = $("otaBranch").value.trim() || "main";
      payload.ota = ota;
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
    const pp = $("btnMusicPlayPause");
    if (pp) pp.textContent = m.buffering ? "…" : (m.playing ? "⏸" : "▶");
    const lk = $("btnMusicLike");
    if (lk) {
      lk.textContent = m.liked ? "♥" : "♡";
      lk.classList.toggle("btn-danger", !!m.liked);
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

  function applyOtaState(ota, fwVer) {
    ota = ota || {};
    const instVer = ota.installedVersion || fwVer || "1.2.2";
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
    const ban = $("aliceStatusBanner");
    if (ban) {
      if (ast?.lastError) {
        ban.textContent = `Ошибка: ${ast.lastError}`;
        ban.className = "status-banner err";
      } else if (cfgAst && !cfgAst.geminiApiKey && cfgAst.mode === "gemini") {
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
      if (hw.fb5Present) {
        $("fb5StatusBanner").textContent = "Экранчик часов активен";
        $("fb5StatusBanner").className = "status-banner ok";
      } else {
        $("fb5StatusBanner").textContent = "Передний экранчик отключён";
        $("fb5StatusBanner").className = "status-banner warn";
      }
    }

    const muted = cfg.audio?.micMuted;
    if ($("btnQuickMute")) {
      $("btnQuickMute").textContent = muted ? "Микрофон: ВЫКЛ" : "Микрофон: ВКЛ";
      $("btnQuickMute").className = "btn btn-sm btn-danger" + (muted ? " active" : "");
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

    if (otaPendingRestart && !d.ota?.busy && d.ota?.state !== "restarting") {
      otaPendingRestart = false;
      showToast("Прошивка OpenStation Max v" + (d.firmwareVersion || "1.2.2") + " активна!");
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

      if ($("actShort")) $("actShort").value = cfg.input?.actionShort || "assistant";
      if ($("actLong")) $("actLong").value = cfg.input?.actionLong || "led_cycle";
      if ($("rotaryInvert")) $("rotaryInvert").checked = !!cfg.input?.rotaryInvert;
      if ($("rotaryStep")) $("rotaryStep").value = cfg.input?.rotaryStep ?? 1;
      if ($("devHost")) $("devHost").value = cfg.deviceHostname || "station-max";

      if (cfg.assistant) {
        if ($("astEnabled")) $("astEnabled").checked = !!cfg.assistant.enabled;
        if ($("astMode")) $("astMode").value = cfg.assistant.mode || "gemini";
        if ($("astApiKey")) $("astApiKey").value = cfg.assistant.geminiApiKey || "";
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
        if ($("astFollowUp")) $("astFollowUp").value = cfg.assistant.followUpSec ?? 8;
        if ($("astMaxSess")) $("astMaxSess").value = cfg.assistant.maxSessionSec ?? 120;
      }

      if ($("wyoEn")) $("wyoEn").checked = !!cfg.wyoming?.enabled;
      if ($("wyoName")) $("wyoName").value = cfg.wyoming?.deviceName || "Station Max";
      if ($("wyoVad")) $("wyoVad").value = cfg.wyoming?.vadThreshold ?? 450;
      if ($("wyoAlways")) $("wyoAlways").checked = !!cfg.wyoming?.alwaysStream;

      if ($("mqttEn")) $("mqttEn").checked = !!cfg.mqtt?.enabled;
      if ($("mqttHost")) $("mqttHost").value = cfg.mqtt?.host || "";
      if ($("mqttPort")) $("mqttPort").value = cfg.mqtt?.port ?? 1883;
      if ($("mqttUser")) $("mqttUser").value = cfg.mqtt?.username || "";
      if ($("mqttPass")) $("mqttPass").value = cfg.mqtt?.password || "";
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

  [
    "ringColor", "ringSecColor", "ringAutoBrightness",
    "scrMode", "scrText", "scrColor", "scrAutoBri",
    "eqPreset", "actShort", "actLong", "rotaryInvert", "rotaryStep",
    "wyoEn", "wyoAlways", "astEnabled", "astMode", "astVoice", "astModel",
    "astPrompt", "astWakeEn", "astChime", "astSearch", "astEchoGuard",
    "astFollowUp", "astMaxSess", "otaAutoCheck", "otaSyncBootanim", "otaRepo", "otaBranch"
  ].forEach((id) => {
    if ($(id)) $(id).addEventListener("change", scheduleConfigPush);
  });

  if ($("btnSaveAll")) $("btnSaveAll").addEventListener("click", () => pushConfig(true));
  if ($("btnSaveAlice")) $("btnSaveAlice").addEventListener("click", () => pushConfig(true));
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
      await api("/api/music/command", "POST", payload);
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
        const res = await fetch("/api/bootanim/upload", {
          method: "POST",
          headers: { "Content-Type": "application/zip" },
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
              showToast("Прошивка обновлена до v" + (msg.data.installedVersion || "1.2.2"));
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
    wizStep = Math.max(1, Math.min(4, step));
    document.querySelectorAll(".wiz-step-pill").forEach((b) => {
      b.classList.toggle("active", parseInt(b.dataset.step, 10) === wizStep);
    });
    for (let i = 1; i <= 4; i++) {
      const p = $("wizStep" + i);
      if (p) p.classList.toggle("active", i === wizStep);
    }
    if ($("btnWizPrev")) $("btnWizPrev").style.visibility = wizStep > 1 ? "visible" : "hidden";
    if ($("btnWizNext")) $("btnWizNext").style.display = wizStep < 4 ? "inline-flex" : "none";
    if ($("btnWizFinish")) $("btnWizFinish").style.display = wizStep === 4 ? "inline-flex" : "none";
    if ($("wizProgressText")) $("wizProgressText").textContent = `Шаг ${wizStep} из 4`;
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
      $("wizFwBadge").textContent = `v${d.firmwareVersion || "1.2.2"}`;
    }
    if ($("sysAutoInfo")) {
      $("sysAutoInfo").textContent = `Версия v${d.firmwareVersion || "1.2.2"} · Все службы работают автономно внутри станции (${d.ip || "local"}).`;
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
        $("wizMusicBanner").textContent = "Аккаунт Яндекс Музыки не подключён (опционально — можно войти токеном или с ТВ).";
        $("wizMusicBanner").className = "status-banner warn";
      }
    }

    if ($("wizChecklist")) {
      const hasKey = !!(cfg.assistant?.geminiApiKey);
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
  if ($("btnWizYmTv")) $("btnWizYmTv").addEventListener("click", () => sendMusicCmd({ cmd: "launch_music" }, "Открываем Яндекс Музыку на ТВ"));
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
    const ver = state?.ota?.latestVersion || state?.firmwareVersion || "1.2.2";
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
