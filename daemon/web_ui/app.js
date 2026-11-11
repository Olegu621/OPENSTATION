(function () {
  const $ = (id) => document.getElementById(id);
  let state = null;
  let ws = null;
  let wsReconnectTimer = null;
  let audioCtx = null;
  let listeningLive = false;
  let nextAudioTime = 0;
  let saveTimer = null;
  let volTimer = null;
  let ampVolTimer = null;
  let ampVolPendingUntil = 0;
  let toastTimer = null;
  let lastHistSig = "";
  let lastAlsaSig = "";
  let wizStep = 1;
  let wizPopulatedOnce = false;
  let otaPendingRestart = false;

  function showToast(msg, isErr = false) {
    const t = $("toast");
    if (!t) return;
    t.textContent = msg;
    t.className = "toast show" + (isErr ? " err" : "");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.classList.remove("show"); }, 2200);
  }

  // Build 24 SVG LED circles in a ring
  const svg = $("ringSvg");
  const dots = [];
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

  // Build 16 mic bars
  const micBars = $("micBars");
  const micFills = [];
  for (let i = 0; i < 16; i++) {
    const col = document.createElement("div");
    col.className = "mic-col";
    const fill = document.createElement("div");
    fill.className = "mic-bar-fill";
    fill.style.height = "3px";
    const lbl = document.createElement("div");
    lbl.className = "mic-bar-lbl";
    lbl.textContent = "" + i;
    col.appendChild(fill);
    col.appendChild(lbl);
    micBars.appendChild(col);
    micFills.push(fill);
  }

  // Tabs
  document.querySelectorAll(".tab").forEach((btn) => {
    btn.addEventListener("click", () => {
      document.querySelectorAll(".tab").forEach((b) => b.classList.remove("active"));
      document.querySelectorAll(".panel").forEach((p) => p.classList.remove("active"));
      btn.classList.add("active");
      $("tab-" + btn.dataset.tab).classList.add("active");
    });
  });

  async function api(path, method = "GET", body = null) {
    const opts = { method, headers: {} };
    if (body !== null) {
      opts.headers["Content-Type"] = "application/json";
      opts.body = JSON.stringify(body);
    }
    const res = await fetch(path, opts);
    if (!res.ok) {
      throw new Error(`HTTP ${res.status}`);
    }
    return res.json();
  }

  function scheduleConfigPush() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => pushConfig(false), 120);
  }

  async function pushConfig(notify = false) {
    saveTimer = null;
    const payload = {
      deviceHostname: $("devHost").value.trim() || "station-max",
      ledRing: {
        mode: document.querySelector("#ringModes .seg.active")?.dataset.mode || "rainbow",
        color: $("ringColor").value,
        secondaryColor: $("ringSecColor").value,
        brightness: parseInt($("ringBrightness").value, 10),
        autoBrightness: $("ringAutoBrightness").checked,
        minAutoBrightness: parseInt($("ringMinAuto").value, 10),
        maxAutoBrightness: parseInt($("ringMaxAuto").value, 10),
        speed: parseInt($("ringSpeed").value, 10),
        ringOffset: parseInt($("ringOffset").value, 10),
        clockwise: $("ringClockwise").value === "true",
        bgrOrderOverride: $("ringBgrOverride").checked
      },
      ledScreen: {
        mode: $("scrMode").value,
        text: $("scrText").value,
        color: $("scrColor").value,
        brightness: parseInt($("scrBri").value, 10),
        autoBrightness: $("scrAutoBri").checked,
        scrollSpeed: parseInt($("scrSpeed").value, 10)
      },
      audio: {
        outputRoute: document.querySelector("#audioRoutes .seg.active")?.dataset.route || "auto",
        wooferEq: $("wooferEq").checked,
        wooferDrc: $("wooferDrc").checked,
        tweetersEq: $("tweetersEq").checked,
        tweetersDrc: $("tweetersDrc").checked,
        eqPreset: $("eqPreset").value,
        pdmGain: parseInt($("pdmGain").value, 10),
        pdmFilter: parseInt($("pdmFilter").value, 10),
        loopbackEnable: $("loopbackEn").checked,
        aecEnable: $("aecEn").checked,
        nsEnable: $("nsEn").checked,
        captureMode: $("captureMode").value,
        primaryMicChannel: parseInt($("primaryMicCh").value, 10),
        loopbackChannel: parseInt($("loopbackCh").value, 10)
      },
      input: {
        rotaryInvert: $("rotaryInvert").checked,
        rotaryStep: parseInt($("rotaryStep").value, 10) || 1,
        actionShort: $("actShort").value,
        actionLong: $("actLong").value
      },
      assistant: {
        enabled: $("astEnabled").checked,
        mode: $("astMode").value,
        geminiApiKey: $("astApiKey").value.trim(),
        geminiModel: $("astModel").value.trim() || "gemini-3.8-live",
        geminiProxyHost: $("astProxyHost") ? $("astProxyHost").value.trim() : "ai-pool.comss.one",
        voiceName: $("astVoice").value || "Aoede",
        systemPrompt: $("astPrompt").value,
        wakeWordEnabled: $("astWakeEn").checked,
        kwsKeywords: $("astKeywords").value.trim() || "▁а ли са @алиса",
        kwsThreshold: parseFloat($("astKwsThr").value) || 0.20,
        kwsScore: parseFloat($("astKwsScore").value) || 1.5,
        kwsThreads: parseInt($("astKwsThreads").value, 10) || 1,
        chimeEnabled: $("astChime").checked,
        googleSearch: $("astSearch").checked,
        echoGuard: $("astEchoGuard").checked,
        preRollMs: Number.isFinite(parseInt($("astPreRoll").value, 10)) ? parseInt($("astPreRoll").value, 10) : 450,
        followUpSec: Number.isFinite(parseInt($("astFollowUp").value, 10)) ? parseInt($("astFollowUp").value, 10) : 8,
        maxSessionSec: parseInt($("astMaxSess").value, 10) || 120,
        vadSilenceMs: parseInt($("astVadSilence").value, 10) || 900
      },
      wyoming: {
        enabled: $("wyoEn").checked,
        deviceName: $("wyoName").value.trim() || "Station Max",
        vadThreshold: parseInt($("wyoVad").value, 10) || 450,
        alwaysStream: $("wyoAlways").checked
      },
      mqtt: {
        enabled: $("mqttEn").checked,
        host: $("mqttHost").value.trim(),
        port: parseInt($("mqttPort").value, 10) || 1883,
        username: $("mqttUser").value,
        password: $("mqttPass").value,
        baseTopic: $("mqttTopic").value.trim() || "openstation/station_max"
      },
      ota: {
        autoCheck: $("otaAutoCheck") ? $("otaAutoCheck").checked : true,
        repo: ($("otaRepo") ? $("otaRepo").value.trim() : "") || "Olegu621/OPENSTATION",
        branch: ($("otaBranch") ? $("otaBranch").value.trim() : "") || "main"
      }
    };
    try {
      const next = await api("/api/config", "POST", payload);
      applyTelemetry(next, false);
      if (notify) showToast("Настройки сохранены и применены");
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
      $("aliceLiveUser").textContent = liveU ? ("Вы: " + liveU) : "";
      $("aliceLiveModel").textContent = liveM ? ("Алиса: " + liveM) : "";
    } else {
      box.style.display = "none";
      $("aliceLiveUser").textContent = "";
      $("aliceLiveModel").textContent = "";
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
      rSpan.textContent = item.role === "user" ? "Вы" : (item.role === "tool" ? "Команда устройству" : "Алиса");
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
    rSpan.textContent = role === "user" ? "Вы" : (role === "tool" ? "Команда устройству" : "Алиса");
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
      const acctStr = m.hasAuth ? `@${m.userLogin || "OK"}${m.hasPlus ? " · Плюс" : ""}` : "нет входа";
      const trackStr = m.title ? `${m.artist || "?"} — ${m.title}` : "Нет активного трека";
      const codecStr = m.codecLabel ? ` [${m.codecLabel}]` : "";
      if (errClean) {
        ban.textContent = `⚠ ${errClean} (${acctStr})`;
        ban.className = "status-banner warn";
      } else if (m.buffering) {
        ban.textContent = `⏳ Буферизация: ${trackStr}${codecStr}`;
        ban.className = "status-banner warn";
      } else if (m.playing) {
        ban.textContent = `▶ ${m.sourceLabel || "Моя волна"}: ${trackStr}${codecStr} · ${acctStr}`;
        ban.className = "status-banner ok";
      } else if (m.paused && m.title) {
        ban.textContent = `⏸ Пауза: ${trackStr}${codecStr} · ${acctStr}`;
        ban.className = "status-banner";
      } else {
        ban.textContent = `Готов (${acctStr}) · Нажмите «🌊 Моя волна» или введите запрос`;
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

  function applyOtaState(ota, fwVer) {
    ota = ota || {};
    const instVer = ota.installedVersion || fwVer || "1.2.1";
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
      const changedList = Array.isArray(ota.changedComponents) && ota.changedComponents.length
        ? ` (${ota.changedComponents.join(", ")})`
        : "";
      if (st === "checking") {
        ban.textContent = `⏳ ${ota.progressText || "Проверка GitHub..."}`;
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
        ban.textContent = `✗ ${ota.error || ota.progressText || "Ошибка OTA"}`;
        ban.className = "status-banner err";
      } else {
        ban.textContent = `Текущая версия v${instVer} · Репозиторий ${ota.repo || "Olegu621/OPENSTATION"}`;
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
        cl.textContent = `Список изменений (v${ota.latestVersion || instVer}): ${ota.changelog}`;
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
      bww.textContent = ww.ready
        ? `KWS: активен (${ww.detections || 0})`
        : `KWS: ${ww.status || "выкл"}`;
      bww.className = "badge " + (ww.ready ? "ok" : "warn");
    }
    const ban = $("aliceStatusBanner");
    if (ban) {
      if (ast?.lastError) {
        ban.textContent = `Ошибка: ${ast.lastError}`;
        ban.className = "status-banner err";
      } else if (cfgAst && !cfgAst.geminiApiKey && cfgAst.mode === "gemini") {
        ban.textContent = "Укажите Gemini API Key справа и нажмите «Сохранить»";
        ban.className = "status-banner warn";
      } else if (ast?.active) {
        ban.textContent = `Алиса активна: ${stRu}${ast.lastTrigger ? " · триггер: " + ast.lastTrigger : ""}`;
        ban.className = "status-banner live";
      } else {
        ban.textContent = `Готова (${ast?.model || "gemini-3.8-live"}) · KWS: ${ww.status || "--"} · Диалогов: ${ast?.sessionsCount || 0}`;
        ban.className = "status-banner " + (ww.ready ? "ok" : "");
      }
    }
  }

  function formatTrimLabel(trimSteps, regVal) {
    const t = Number.isFinite(trimSteps) ? trimSteps : 0;
    const db = (t * 0.5).toFixed(1);
    const sign = t >= 0 ? "+" : "";
    const regStr = typeof regVal === "number" ? ` · рег. ${regVal}` : "";
    return `${sign}${db} дБ${regStr}`;
  }

  function applyTelemetry(d, populateInputs) {
    if (!d) return;
    state = d;
    const hw = d.hardware || {};
    const cfg = d.config || {};
    const au = d.audio || {};
    const eng = au.engine || {};
    const mixer = au.mixer || {};
    const ast = d.assistant || {};
    const wyo = d.wyoming || {};
    const mq = d.mqtt || {};

    $("sysSubtitle").textContent = `${d.device || ""} | IP: ${d.ip || "local"} | ${d.hostname || "station-max"}.local`;
    $("badgeTemp").textContent = `${(hw.socTempC || 0).toFixed(1)} °C`;
    $("badgeLux").textContent = `${(hw.lux || 0).toFixed(1)} lx`;
    if (typeof au.volumePercent === "number") {
      $("badgeVol").textContent = `Громк: ${au.volumePercent}%`;
      if (!volTimer && document.activeElement !== $("sysVol")) {
        $("sysVol").value = au.volumePercent;
        $("lblSysVol").textContent = `${au.volumePercent}%`;
      }
    }
    $("badgeJack").textContent = mixer.headphonesJack ? "Джек 3.5мм: Подключен" : "Джек: Выкл (Динамики)";
    $("badgeScreen").textContent = hw.fb5Present ? `Экран fb5: ${hw.fb5Width}x${hw.fb5Height}` : "Экран fb5: Отключен";
    $("badgeScreen").className = "badge " + (hw.fb5Present ? "ok" : "warn");
    $("ringBrightnessVal").textContent = hw.effectiveBrightness ?? "--";

    if (hw.fb5Present) {
      $("fb5StatusBanner").textContent = `Подключен: ${hw.fb5Width}x${hw.fb5Height}, ${hw.fb5Bpp} bpp`;
      $("fb5StatusBanner").className = "status-banner ok";
    } else {
      $("fb5StatusBanner").textContent = "Экранчик физически отключен (автоопределение активно — подключите шлейф при сборке).";
      $("fb5StatusBanner").className = "status-banner warn";
    }

    const muted = cfg.audio?.micMuted;
    $("btnQuickMute").textContent = muted ? "Микрофон: ВЫКЛ (MUTE)" : "Микрофон: ВКЛ";
    $("btnQuickMute").className = "btn btn-sm btn-danger" + (muted ? " active" : "");

    // Update 24 SVG LEDs
    const px = hw.ringPixels || [];
    for (let i = 0; i < 24 && i < px.length; i++) {
      const [r, g, b] = px[i];
      dots[i].setAttribute("fill", `rgb(${r},${g},${b})`);
    }

    // Update 16ch Mic RMS bars
    const rmsArr = eng.channelRms || [];
    for (let i = 0; i < 16; i++) {
      const val = rmsArr[i] || 0;
      const pct = Math.min(100, Math.max(2, Math.sqrt(val / 3500) * 100));
      micFills[i].style.height = pct.toFixed(0) + "%";
    }
    $("micBackendInfo").textContent = `Backend: ${eng.backend || "--"} | ${eng.sampleRate || 0} Гц | ${eng.channels || 0} кан. | Mono RMS: ${Math.round(eng.monoRms || 0)}`;
    $("btnCaptureToggle").textContent = eng.captureEnabled === false ? "Возобновить захват" : "Пауза захвата (для ТВ-приложений)";
    const alsaSig = JSON.stringify(mixer);
    if (alsaSig !== lastAlsaSig) {
      lastAlsaSig = alsaSig;
      $("alsaDump").textContent = JSON.stringify(mixer, null, 2);
    }

    $("wyoStatusBanner").textContent = wyo.clientConnected
      ? `Home Assistant подключен (${wyo.peerIp})${wyo.streaming ? " • СТРИМИНГ" : ""}${wyo.lastTranscript ? " • Текст: " + wyo.lastTranscript : ""}`
      : `Порт ${wyo.port || 10700} открыт • Ожидание подключения Home Assistant`;
    $("wyoStatusBanner").className = "status-banner " + (wyo.clientConnected ? "ok" : "warn");

    $("mqttStatusBanner").textContent = mq.enabled ? (mq.connected ? `Подключен к ${mq.host}:${mq.port}` : `Подключение к ${mq.host}:${mq.port}...`) : "Отключено";
    $("mqttStatusBanner").className = "status-banner " + (mq.connected ? "ok" : "");

    applyAliceBanner(ast.state || "idle", ast, cfg.assistant);
    applyMusicState(d.music || {});
    applyOtaState(d.ota || {}, d.firmwareVersion);
    if (otaPendingRestart && !d.ota?.busy && d.ota?.state !== "restarting") {
      otaPendingRestart = false;
      showToast("Прошивка OpenStation Max v" + (d.firmwareVersion || "1.2.1") + " активна!");
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
      $("ringColor").value = cfg.ledRing.color || "#00d4ff";
      $("ringSecColor").value = cfg.ledRing.secondaryColor || "#7c3aed";
      $("ringBrightness").value = cfg.ledRing.brightness ?? 160;
      $("lblBrightness").textContent = $("ringBrightness").value;
      $("ringAutoBrightness").checked = !!cfg.ledRing.autoBrightness;
      $("ringMinAuto").value = cfg.ledRing.minAutoBrightness ?? 18;
      $("lblMinAuto").textContent = $("ringMinAuto").value;
      $("ringMaxAuto").value = cfg.ledRing.maxAutoBrightness ?? 255;
      $("lblMaxAuto").textContent = $("ringMaxAuto").value;
      $("ringSpeed").value = cfg.ledRing.speed ?? 55;
      $("lblSpeed").textContent = $("ringSpeed").value;
      $("ringOffset").value = cfg.ledRing.ringOffset ?? 0;
      $("lblOffset").textContent = $("ringOffset").value;
      $("ringClockwise").value = cfg.ledRing.clockwise ? "true" : "false";
      $("ringBgrOverride").checked = !!cfg.ledRing.bgrOrderOverride;

      $("scrMode").value = cfg.ledScreen?.mode || "clock";
      $("scrText").value = cfg.ledScreen?.text || "OPEN STATION MAX";
      $("scrColor").value = cfg.ledScreen?.color || "#ffffff";
      $("scrBri").value = cfg.ledScreen?.brightness ?? 100;
      $("lblScrBri").textContent = $("scrBri").value;
      $("scrAutoBri").checked = !!cfg.ledScreen?.autoBrightness;
      $("scrSpeed").value = cfg.ledScreen?.scrollSpeed ?? 45;
      $("lblScrSpeed").textContent = $("scrSpeed").value;

      document.querySelectorAll("#audioRoutes .seg").forEach((b) => {
        b.classList.toggle("active", b.dataset.route === cfg.audio?.outputRoute);
      });
      const wTrim = cfg.audio?.wooferTrim ?? 0;
      const tTrim = cfg.audio?.tweetersTrim ?? 0;
      const hTrim = cfg.audio?.headphoneTrim ?? 0;
      $("wooferVol").value = wTrim;
      $("lblWooferVol").textContent = formatTrimLabel(wTrim, mixer.wooferVolume ?? cfg.audio?.wooferVolume);
      $("tweetersVol").value = tTrim;
      $("lblTweetersVol").textContent = formatTrimLabel(tTrim, mixer.tweetersVolume ?? cfg.audio?.tweetersVolume);
      $("hpVol").value = hTrim;
      $("lblHpVol").textContent = formatTrimLabel(hTrim, mixer.headphoneVolume ?? cfg.audio?.headphoneVolume);
      $("wooferEq").checked = !!cfg.audio?.wooferEq;
      $("wooferDrc").checked = !!cfg.audio?.wooferDrc;
      $("tweetersEq").checked = !!cfg.audio?.tweetersEq;
      $("tweetersDrc").checked = !!cfg.audio?.tweetersDrc;
      $("eqPreset").value = cfg.audio?.eqPreset || "balanced";
      $("pdmGain").value = cfg.audio?.pdmGain ?? 3;
      $("lblPdmGain").textContent = $("pdmGain").value;
      $("pdmFilter").value = cfg.audio?.pdmFilter ?? 1;
      $("loopbackEn").checked = !!cfg.audio?.loopbackEnable;
      $("aecEn").checked = !!cfg.audio?.aecEnable;
      $("nsEn").checked = !!cfg.audio?.nsEnable;
      $("captureMode").value = cfg.audio?.captureMode || "alsa_16ch";
      $("primaryMicCh").value = String(cfg.audio?.primaryMicChannel ?? 1);
      $("loopbackCh").value = String(cfg.audio?.loopbackChannel ?? 8);

      $("actShort").value = cfg.input?.actionShort || "assistant";
      $("actLong").value = cfg.input?.actionLong || "led_cycle";
      $("rotaryInvert").checked = !!cfg.input?.rotaryInvert;
      $("rotaryStep").value = cfg.input?.rotaryStep ?? 1;
      $("devHost").value = cfg.deviceHostname || "station-max";

      if (cfg.assistant) {
        $("astEnabled").checked = !!cfg.assistant.enabled;
        $("astMode").value = cfg.assistant.mode || "gemini";
        $("astApiKey").value = cfg.assistant.geminiApiKey || "";
        $("astModel").value = cfg.assistant.geminiModel || "gemini-3.8-live";
        if ($("astProxyHost")) {
          $("astProxyHost").value =
            cfg.assistant.geminiProxyHost !== undefined ? cfg.assistant.geminiProxyHost : "ai-pool.comss.one";
        }
        $("astVoice").value = cfg.assistant.voiceName || "Aoede";
        $("astPrompt").value = cfg.assistant.systemPrompt || "";
        $("astWakeEn").checked = cfg.assistant.wakeWordEnabled !== false;
        $("astKeywords").value = cfg.assistant.kwsKeywords || "▁а ли са @алиса";
        $("astKwsThr").value = cfg.assistant.kwsThreshold ?? 0.20;
        $("lblKwsThr").textContent = parseFloat($("astKwsThr").value).toFixed(2);
        $("astKwsScore").value = cfg.assistant.kwsScore ?? 1.5;
        $("lblKwsScore").textContent = parseFloat($("astKwsScore").value).toFixed(1);
        $("astKwsThreads").value = cfg.assistant.kwsThreads ?? 1;
        $("astChime").checked = cfg.assistant.chimeEnabled !== false;
        $("astSearch").checked = cfg.assistant.googleSearch === true;
        $("astEchoGuard").checked = cfg.assistant.echoGuard !== false;
        $("astPreRoll").value = cfg.assistant.preRollMs ?? 450;
        $("astFollowUp").value = cfg.assistant.followUpSec ?? 8;
        $("astMaxSess").value = cfg.assistant.maxSessionSec ?? 120;
        $("astVadSilence").value = cfg.assistant.vadSilenceMs ?? 900;
      }

      $("wyoEn").checked = !!cfg.wyoming?.enabled;
      $("wyoName").value = cfg.wyoming?.deviceName || "Station Max";
      $("wyoVad").value = cfg.wyoming?.vadThreshold ?? 450;
      $("wyoAlways").checked = !!cfg.wyoming?.alwaysStream;

      $("mqttEn").checked = !!cfg.mqtt?.enabled;
      $("mqttHost").value = cfg.mqtt?.host || "";
      $("mqttPort").value = cfg.mqtt?.port ?? 1883;
      $("mqttUser").value = cfg.mqtt?.username || "";
      $("mqttPass").value = cfg.mqtt?.password || "";
      $("mqttTopic").value = cfg.mqtt?.baseTopic || "openstation/station_max";
      if ($("otaRepo")) $("otaRepo").value = cfg.ota?.repo || "Olegu621/OPENSTATION";
      if ($("otaBranch")) $("otaBranch").value = cfg.ota?.branch || "main";
      if ($("otaAutoCheck")) $("otaAutoCheck").checked = cfg.ota?.autoCheck !== false;
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
      if (cfg.audio?.eqPreset && document.activeElement !== $("eqPreset")) {
        $("eqPreset").value = cfg.audio.eqPreset;
      }
      if (cfg.audio?.captureMode && document.activeElement !== $("captureMode")) {
        $("captureMode").value = cfg.audio.captureMode;
      }
      const curWoo = mixer.wooferVolume ?? cfg.audio?.wooferVolume;
      const wTrim = cfg.audio?.wooferTrim ?? 0;
      if (Date.now() > ampVolPendingUntil && document.activeElement !== $("wooferVol")) {
        $("wooferVol").value = wTrim;
        $("lblWooferVol").textContent = formatTrimLabel(wTrim, curWoo);
      }
      const curTwe = mixer.tweetersVolume ?? cfg.audio?.tweetersVolume;
      const tTrim = cfg.audio?.tweetersTrim ?? 0;
      if (Date.now() > ampVolPendingUntil && document.activeElement !== $("tweetersVol")) {
        $("tweetersVol").value = tTrim;
        $("lblTweetersVol").textContent = formatTrimLabel(tTrim, curTwe);
      }
      const curHp = mixer.headphoneVolume ?? cfg.audio?.headphoneVolume;
      const hTrim = cfg.audio?.headphoneTrim ?? 0;
      if (Date.now() > ampVolPendingUntil && document.activeElement !== $("hpVol")) {
        $("hpVol").value = hTrim;
        $("lblHpVol").textContent = formatTrimLabel(hTrim, curHp);
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

  // Live slider value labels + immediate config sync
  const bindIds = [
    ["ringBrightness", "lblBrightness"], ["ringMinAuto", "lblMinAuto"], ["ringMaxAuto", "lblMaxAuto"],
    ["ringSpeed", "lblSpeed"], ["ringOffset", "lblOffset"], ["scrBri", "lblScrBri"], ["scrSpeed", "lblScrSpeed"],
    ["pdmGain", "lblPdmGain"]
  ];
  bindIds.forEach(([id, lbl]) => {
    $(id).addEventListener("input", () => {
      $(lbl).textContent = $(id).value;
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
        showToast("Ошибка усиления: " + e.message, true);
      }
    }, 90);
  }
  [
    ["wooferVol", "lblWooferVol", "wooferTrim", "wooferVolume"],
    ["tweetersVol", "lblTweetersVol", "tweetersTrim", "tweetersVolume"],
    ["hpVol", "lblHpVol", "headphoneTrim", "headphoneVolume"]
  ].forEach(([id, lbl, field, mixerKey]) => {
    $(id).addEventListener("input", () => {
      const val = parseInt($(id).value, 10) || 0;
      const curReg = state?.audio?.mixer?.[mixerKey] ?? state?.config?.audio?.[mixerKey];
      $(lbl).textContent = formatTrimLabel(val, curReg);
      pushAmpVolumePatch(field, val);
    });
  });

  $("astKwsThr").addEventListener("input", () => {
    $("lblKwsThr").textContent = parseFloat($("astKwsThr").value).toFixed(2);
    scheduleConfigPush();
  });
  $("astKwsScore").addEventListener("input", () => {
    $("lblKwsScore").textContent = parseFloat($("astKwsScore").value).toFixed(1);
    scheduleConfigPush();
  });

  function pushSystemVolume(pct) {
    const clamped = Math.max(0, Math.min(100, Math.round(pct)));
    $("sysVol").value = clamped;
    $("lblSysVol").textContent = `${clamped}%`;
    $("badgeVol").textContent = `Громк: ${clamped}%`;
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
  $("sysVol").addEventListener("input", () => pushSystemVolume(parseInt($("sysVol").value, 10) || 0));
  $("btnVolDown").addEventListener("click", () => pushSystemVolume((parseInt($("sysVol").value, 10) || 0) - 5));
  $("btnVolUp").addEventListener("click", () => pushSystemVolume((parseInt($("sysVol").value, 10) || 0) + 5));

  ["ringColor", "ringSecColor", "ringAutoBrightness", "ringClockwise", "ringBgrOverride",
   "scrMode", "scrText", "scrColor", "scrAutoBri",
   "wooferEq", "wooferDrc", "tweetersEq", "tweetersDrc", "eqPreset",
   "pdmFilter", "captureMode", "primaryMicCh", "loopbackCh", "loopbackEn", "aecEn", "nsEn",
   "actShort", "actLong", "rotaryInvert", "rotaryStep", "wyoEn", "wyoAlways",
   "astEnabled", "astMode", "astVoice", "astModel", "astPrompt", "astWakeEn", "astKeywords",
   "astKwsThreads", "astChime", "astSearch", "astEchoGuard", "astPreRoll", "astFollowUp", "astMaxSess", "astVadSilence",
   "otaAutoCheck", "otaRepo", "otaBranch"].forEach((id) => {
    if ($(id)) $(id).addEventListener("change", scheduleConfigPush);
  });

  $("btnSaveAll").addEventListener("click", () => pushConfig(true));
  $("btnSaveAlice").addEventListener("click", () => pushConfig(true));
  $("btnToggleKeyVis").addEventListener("click", () => {
    const inp = $("astApiKey");
    const show = inp.type === "password";
    inp.type = show ? "text" : "password";
    $("btnToggleKeyVis").textContent = show ? "Скрыть" : "Показать";
  });

  async function triggerAlice() {
    try {
      await api("/api/assistant/trigger", "POST", { reason: "webui" });
      showToast("Алиса слушает...");
    } catch (e) {
      showToast("Не удалось запустить Алису (проверьте ключ/режим)", true);
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
    const val = $("aliceTextPrompt").value.trim();
    if (!val) return;
    $("aliceTextPrompt").value = "";
    try {
      await api("/api/assistant/text", "POST", { text: val });
    } catch (e) {
      showToast("Ошибка отправки Алисе: " + e.message, true);
    }
  }

  $("btnQuickAlice").addEventListener("click", () => {
    if (state?.assistant?.active) stopAlice();
    else triggerAlice();
  });
  $("btnAliceTrigger").addEventListener("click", triggerAlice);
  $("btnAliceStop").addEventListener("click", stopAlice);
  $("btnAliceSendText").addEventListener("click", sendAliceText);
  $("aliceTextPrompt").addEventListener("keydown", (e) => {
    if (e.key === "Enter") sendAliceText();
  });

  async function sendMusicCmd(payload, toastOk) {
    try {
      await api("/api/music/command", "POST", payload);
      if (toastOk) showToast(toastOk);
    } catch (e) {
      showToast("Ошибка Яндекс Музыки: " + e.message, true);
    }
  }
  $("btnQuickMusic").addEventListener("click", () => {
    const m = state?.music || {};
    if (m.playing || m.paused) {
      sendMusicCmd({ cmd: "music_action", action: "PLAY_PAUSE" });
    } else {
      sendMusicCmd({ cmd: "play_my_wave", mood: "any", openUi: false }, "Запускаем Мою волну...");
    }
  });
  $("btnLaunchMusic").addEventListener("click", () => sendMusicCmd({ cmd: "launch_music" }, "Открываем Яндекс Музыку на ТВ"));
  $("btnMusicWave").addEventListener("click", () => sendMusicCmd({ cmd: "play_my_wave", mood: "any", openUi: false }, "Запускаем Мою волну..."));
  $("btnMusicPrev").addEventListener("click", () => sendMusicCmd({ cmd: "music_action", action: "PREV" }));
  $("btnMusicPlayPause").addEventListener("click", () => sendMusicCmd({ cmd: "music_action", action: "PLAY_PAUSE" }));
  $("btnMusicNext").addEventListener("click", () => sendMusicCmd({ cmd: "music_action", action: "NEXT" }));
  $("btnMusicLike").addEventListener("click", () => sendMusicCmd({ cmd: "music_action", action: "LIKE" }));
  $("btnMusicLikedList").addEventListener("click", () => sendMusicCmd({ cmd: "music_action", action: "PLAY_LIKED" }, "Включаем «Мне нравится»..."));
  function searchAndPlayMusic() {
    const q = $("musicSearchInput").value.trim();
    if (!q) return;
    $("musicSearchInput").value = "";
    sendMusicCmd({ cmd: "play_query", query: q, openUi: false }, `Ищем и включаем: ${q}`);
  }
  $("btnMusicSearch").addEventListener("click", searchAndPlayMusic);
  $("musicSearchInput").addEventListener("keydown", (e) => {
    if (e.key === "Enter") searchAndPlayMusic();
  });
  $("btnPushMusicOAuth").addEventListener("click", async () => {
    const tok = $("musicOAuthInput").value.trim();
    if (!tok) return;
    await sendMusicCmd({ cmd: "music_oauth", token: tok, autoPlay: true }, "Токен передан плееру Яндекс Музыка");
    $("musicOAuthInput").value = "";
  });
  $("btnQuickMute").addEventListener("click", async () => {
    if (ws && ws.readyState === 1) {
      ws.send(JSON.stringify({ cmd: "mute_toggle" }));
    } else {
      try {
        const nextMute = !(state?.config?.audio?.micMuted);
        const next = await api("/api/config", "POST", { audio: { micMuted: nextMute } });
        applyTelemetry(next, false);
      } catch (e) {
        showToast("Ошибка Mute: " + e.message, true);
      }
    }
  });
  $("btnQuickVoice").addEventListener("click", async () => {
    try {
      await api("/api/wyoming/trigger", "POST", { duration: 6000 });
      showToast("Голосовой захват Wyoming запущен");
    } catch (e) { showToast("Ошибка Wyoming: " + e.message, true); }
  });
  $("btnCalLed0").addEventListener("click", () => api("/api/led/calibrate", "POST", { mode: 0 }).then(() => showToast("LED #0 выделен (4.5 сек)")).catch(() => {}));
  $("btnCalRgb").addEventListener("click", () => api("/api/led/calibrate", "POST", { mode: -2 }).then(() => showToast("Тест RGB каналов (4.5 сек)")).catch(() => {}));
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
      showToast("Баланс каналов сброшен (0.0 дБ)");
    } catch (e) {
      showToast("Ошибка сброса баланса: " + e.message, true);
    }
  });
  $("btnReloadAmpEq").addEventListener("click", () => api("/api/audio/reload_eq", "POST", {}).then((r) => showToast(r.ok ? "Кроссовер SY6045S перезагружен" : "Не удалось перезагрузить sysfs", !r.ok)).catch(() => {}));
  $("btnTone440").addEventListener("click", () => api("/api/audio/test_tone", "POST", { freq: 440, duration: 700, sweep: false }).then(() => showToast("Тон 440 Гц")).catch(() => {}));
  $("btnToneSweep").addEventListener("click", () => api("/api/audio/test_tone", "POST", { sweep: true, duration: 2200 }).then(() => showToast("Свип 90–4500 Гц")).catch(() => {}));
  $("btnCaptureToggle").addEventListener("click", async () => {
    const cur = state?.audio?.engine?.captureEnabled !== false;
    try {
      await api("/api/audio/capture_toggle", "POST", { enabled: !cur });
      showToast(cur ? "Захват микрофонов приостановлен" : "Захват микрофонов возобновлён");
    } catch (e) { showToast("Ошибка: " + e.message, true); }
  });

  // Remote D-pad & CEC & Text
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
        showToast("HDMI-CEC: " + btn.dataset.cec);
      } catch (e) { showToast("Ошибка CEC: " + e.message, true); }
    });
  });
  $("btnSendRemoteText").addEventListener("click", async () => {
    const val = $("remoteTextInput").value;
    if (!val) return;
    try {
      await api("/api/remote/text", "POST", { text: val });
      $("remoteTextInput").value = "";
      showToast("Текст отправлен на ТВ");
    } catch (e) { showToast("Ошибка отправки текста: " + e.message, true); }
  });
  $("remoteTextInput").addEventListener("keydown", (e) => {
    if (e.key === "Enter") $("btnSendRemoteText").click();
  });

  async function loadApps() {
    try {
      const d = await api("/api/apps");
      const grid = $("appsGrid");
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
        const pk = document.createElement("div");
        pk.className = "pkg";
        pk.textContent = a.package;
        tile.appendChild(nm);
        tile.appendChild(pk);
        tile.addEventListener("click", () => {
          api("/api/apps/launch", "POST", { package: a.package })
            .then(() => showToast("Запуск: " + (a.label || a.package)))
            .catch(() => {});
        });
        grid.appendChild(tile);
      });
    } catch {
      $("appsGrid").textContent = "Не удалось загрузить список приложений";
    }
  }
  $("btnRefreshApps").addEventListener("click", loadApps);

  // Live microphone audio playback in browser via Web Audio API
  $("btnListenLive").addEventListener("click", () => {
    listeningLive = !listeningLive;
    if (listeningLive) {
      if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 16000 });
      if (audioCtx.state === "suspended") audioCtx.resume();
      nextAudioTime = audioCtx.currentTime + 0.05;
      $("btnListenLive").textContent = "Слушать микрофон в браузере (ВКЛ)";
      $("btnListenLive").classList.add("active");
    } else {
      $("btnListenLive").textContent = "Слушать микрофон в браузере (ВЫКЛ)";
      $("btnListenLive").classList.remove("active");
    }
    if (ws && ws.readyState === 1) {
      ws.send(JSON.stringify({ cmd: "mic_audio", enable: listeningLive }));
    }
  });

  $("btnClearLog").addEventListener("click", () => { $("logBox").textContent = ""; });

  function appendLog(line) {
    const box = $("logBox");
    box.textContent += line + "\n";
    if (box.textContent.length > 25000) {
      box.textContent = box.textContent.slice(-18000);
    }
    box.scrollTop = box.scrollHeight;
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
    ws.onopen = () => {
      $("badgeWs").textContent = "WS: Подключен";
      $("badgeWs").className = "badge ok";
      if (listeningLive) ws.send(JSON.stringify({ cmd: "mic_audio", enable: true }));
    };
    ws.onerror = () => {
      try { ws?.close(); } catch {}
    };
    ws.onclose = () => {
      $("badgeWs").textContent = "WS: Переподключение...";
      $("badgeWs").className = "badge warn";
      if (!wsReconnectTimer) {
        wsReconnectTimer = setTimeout(connectWs, 2000);
      }
    };
    ws.onmessage = (ev) => {
      if (typeof ev.data === "string") {
        try {
          const msg = JSON.parse(ev.data);
          if (msg.type === "telemetry") applyTelemetry(msg.data, state === null);
          else if (msg.type === "log") appendLog(msg.line);
          else if (msg.type === "ota") {
            if (state) state.ota = msg.data;
            applyOtaState(msg.data, state?.firmwareVersion);
            if (msg.data?.state === "up_to_date" && msg.data?.progressPct === 100 && !msg.data?.updateAvailable && otaPendingRestart) {
              otaPendingRestart = false;
              showToast("Прошивка обновлена до v" + (msg.data.installedVersion || "1.2.1"));
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
              appendAliceHistoryItem("tool", "Инструмент: " + msg.text);
            } else if (msg.kind === "error" && msg.text) {
              showToast("Алиса: " + msg.text, true);
            }
          }
        } catch {}
      } else if (listeningLive && audioCtx) {
        const pcm16 = new Int16Array(ev.data);
        const buf = audioCtx.createBuffer(1, pcm16.length, 16000);
        const ch = buf.getChannelData(0);
        for (let i = 0; i < pcm16.length; i++) ch[i] = pcm16[i] / 32768.0;
        const src = audioCtx.createBufferSource();
        src.buffer = buf;
        src.connect(audioCtx.destination);
        const now = audioCtx.currentTime;
        if (nextAudioTime < now || nextAudioTime > now + 0.35) nextAudioTime = now + 0.04;
        src.start(nextAudioTime);
        nextAudioTime += buf.duration;
      }
    };
  }

  async function runGeminiLiveTest(keyVal, modelVal, proxyVal, targetEl) {
    if (targetEl) {
      targetEl.textContent = "⏳ Проверка подключения к Gemini Live...";
      if (targetEl.classList.contains("status-banner")) targetEl.className = "status-banner warn";
    }
    try {
      const r = await api("/api/assistant/test", "POST", {
        geminiApiKey: keyVal || "",
        geminiModel: modelVal || "gemini-3.8-live",
        geminiProxyHost: proxyVal ?? "ai-pool.comss.one"
      });
      if (r.ok) {
        const msg = `✓ Gemini Live подключён (маршрут: ${r.via}, ${r.latencyMs} мс)`;
        if (targetEl) {
          targetEl.textContent = msg;
          if (targetEl.classList.contains("status-banner")) targetEl.className = "status-banner ok";
        }
        showToast(msg);
      } else {
        const err = `✗ Ошибка Gemini Live${r.code ? " (" + r.code + ")" : ""}: ${r.error || "нет ответа"}`;
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
    $("btnWizPrev").style.visibility = wizStep > 1 ? "visible" : "hidden";
    $("btnWizNext").style.display = wizStep < 4 ? "inline-flex" : "none";
    $("btnWizFinish").style.display = wizStep === 4 ? "inline-flex" : "none";
    $("wizProgressText").textContent = `Шаг ${wizStep} из 4`;
    if (state) syncWizardTelemetry(state, false);
  }

  function openWizard(step = 1) {
    wizPopulatedOnce = false;
    if (state) syncWizardTelemetry(state, true);
    setWizardStep(step);
    $("wizardModal").style.display = "flex";
  }

  function closeWizard(rememberDismiss = true) {
    $("wizardModal").style.display = "none";
    if (rememberDismiss) sessionStorage.setItem("os_wiz_dismissed", "1");
    if (location.hash === "#wizard") {
      try { history.replaceState(null, "", location.pathname + location.search); } catch {}
    }
  }

  function syncWizardTelemetry(d, populateFields) {
    const hw = d.hardware || {};
    const cfg = d.config || {};
    const au = d.audio || {};
    const eng = au.engine || {};
    const ast = d.assistant || {};
    const ww = ast.wakeWord || {};
    const m = d.music || {};

    if ($("wizFwBadge")) {
      $("wizFwBadge").textContent = `v${d.firmwareVersion || "1.2.1"} · На станции`;
    }
    if ($("sysAutoInfo")) {
      $("sysAutoInfo").textContent = `Версия v${d.firmwareVersion || "1.2.1"} · Сервер работает прямо на станции (${d.ip || "local"}:8080, ${d.hostname || "station-max"}.local). ПК после прошивки не требуется.`;
    }
    if ($("wizDiagIp")) $("wizDiagIp").textContent = `${d.ip || "127.0.0.1"}:${cfg.webPort || 8080}`;
    if ($("wizDiagMdns")) $("wizDiagMdns").textContent = `${d.hostname || "station-max"}.local`;
    if ($("wizDiagHw")) {
      const oled = hw.fb5Present ? `OLED ${hw.fb5Width}x${hw.fb5Height}` : "OLED откл.";
      $("wizDiagHw").textContent = `${(hw.socTempC || 0).toFixed(1)} °C · ${oled}`;
    }
    if ($("wizDiagMic")) {
      $("wizDiagMic").textContent = `${cfg.audio?.captureMode || "alsa_16ch"} · KWS: ${ww.ready ? "Алиса ОК" : (ww.status || "выкл")}`;
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
      const dnsLabel = dnsMode === "hostname" && dnsHost ? `Private DNS: ${dnsHost}` : `DNS: ${dnsMode || "авто"}`;
      $("wizNetBanner").textContent = `✓ Автономный режим · IP: ${d.ip || "local"} · ${dnsLabel} · Мастер синхронизирован с ТВ`;
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
        { ok: true, txt: `Сервер на станции (${d.ip || "OK"}:8080)` },
        { ok: hasKey, txt: hasKey ? "Ключ Gemini Live API задан" : "Ключ Gemini API не задан (Шаг 2)" },
        { ok: !!ww.ready, txt: ww.ready ? "Офлайн фраза «Алиса» активна" : "Wake Word выключен" },
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
      runGeminiLiveTest($("astApiKey").value.trim(), $("astModel").value.trim(), $("astProxyHost")?.value.trim(), $("aliceTestResult"));
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
      const show = inp.type === "password";
      inp.type = show ? "text" : "password";
      $("btnWizToggleKey").textContent = show ? "Скрыть" : "Показать";
    });
  }
  if ($("btnApplyDns")) $("btnApplyDns").addEventListener("click", () => applyDnsHost($("sysDnsHost").value));
  if ($("btnWizApplyDns")) $("btnWizApplyDns").addEventListener("click", () => applyDnsHost($("wizDnsHost").value));

  if ($("btnWizTestGemini")) {
    $("btnWizTestGemini").addEventListener("click", async () => {
      const key = $("wizApiKey").value.trim();
      const proxy = $("wizProxyHost").value.trim();
      await pushPartialConfig({
        assistant: {
          enabled: true,
          mode: "gemini",
          geminiApiKey: key,
          geminiProxyHost: proxy,
          voiceName: $("wizVoice").value,
          wakeWordEnabled: $("wizWakeEn").checked
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
          voiceName: $("wizVoice").value,
          wakeWordEnabled: $("wizWakeEn").checked,
          geminiApiKey: $("wizApiKey").value.trim(),
          geminiProxyHost: $("wizProxyHost").value.trim()
        }
      }, "Параметры Алисы сохранены"));
    }
  });
  if ($("btnWizYmOAuth")) {
    $("btnWizYmOAuth").addEventListener("click", async () => {
      const tok = $("wizYmToken").value.trim();
      if (!tok) return;
      await sendMusicCmd({ cmd: "music_oauth", token: tok, autoPlay: false }, "Токен передан Яндекс Музыке");
      $("wizYmToken").value = "";
    });
  }
  if ($("btnWizYmTv")) $("btnWizYmTv").addEventListener("click", () => sendMusicCmd({ cmd: "launch_music" }, "Открываем Яндекс Музыку на ТВ"));
  if ($("btnWizYmWave")) $("btnWizYmWave").addEventListener("click", () => sendMusicCmd({ cmd: "play_my_wave", mood: "any", openUi: false }, "Запускаем Мою волну..."));
  if ($("btnWizTone440")) $("btnWizTone440").addEventListener("click", () => api("/api/audio/test_tone", "POST", { freq: 440, duration: 700, sweep: false }).then(() => showToast("Тестовый тон 440 Гц")).catch(() => {}));
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
          mode: $("wizRingMode").value,
          color: $("wizRingColor").value,
          autoBrightness: $("wizAutoBri").checked
        }
      }, "Подсветка обновлена"));
    }
  });
  if ($("wizActShort")) {
    $("wizActShort").addEventListener("change", () => pushPartialConfig({ input: { actionShort: $("wizActShort").value } }, "Кнопка Action обновлена"));
  }
  if ($("btnWizCecWake")) {
    $("btnWizCecWake").addEventListener("click", () => api("/api/remote/cec", "POST", { cmd: "wakeup" }).then(() => showToast("Команда HDMI-CEC отправлена ТВ")).catch(() => {}));
  }
  if ($("btnWizFinish")) {
    $("btnWizFinish").addEventListener("click", async () => {
      await pushPartialConfig({
        assistant: {
          geminiApiKey: $("wizApiKey").value.trim(),
          geminiProxyHost: $("wizProxyHost").value.trim(),
          voiceName: $("wizVoice").value,
          wakeWordEnabled: $("wizWakeEn").checked
        },
        ledRing: {
          mode: $("wizRingMode").value,
          color: $("wizRingColor").value,
          autoBrightness: $("wizAutoBri").checked
        },
        audio: {
          outputRoute: $("wizAudioRoute").value,
          eqPreset: $("wizEqPreset").value
        },
        input: {
          actionShort: $("wizActShort").value
        }
      });
      try {
        const next = await api("/api/setup/complete", "POST", {});
        applyTelemetry(next, true);
        closeWizard(true);
        showToast("Мастер настройки завершён! Станция готова.");
      } catch (e) {
        showToast("Ошибка завершения: " + e.message, true);
      }
    });
  }
  if ($("btnRestartDaemon")) {
    $("btnRestartDaemon").addEventListener("click", async () => {
      if (!confirm("Перезапустить демон openstationd на станции? Связь восстановится через 2-3 секунды.")) return;
      try {
        await api("/api/system/restart_daemon", "POST", {});
        showToast("Демон перезапускается...");
      } catch (e) { showToast("Ошибка: " + e.message, true); }
    });
  }
  if ($("btnRebootStation")) {
    $("btnRebootStation").addEventListener("click", async () => {
      if (!confirm("Перезагрузить Яндекс Станцию Макс? Веб-пульт станет недоступен на ~30 секунд.")) return;
      try {
        await api("/api/system/reboot", "POST", {});
        showToast("Команда перезагрузки отправлена станции...");
      } catch (e) { showToast("Ошибка: " + e.message, true); }
    });
  }
  if ($("badgeOtaUpdate")) {
    $("badgeOtaUpdate").addEventListener("click", () => {
      document.querySelectorAll(".tab").forEach((b) => b.classList.toggle("active", b.dataset.tab === "smart"));
      document.querySelectorAll(".panel").forEach((p) => p.classList.toggle("active", p.id === "tab-smart"));
      $("otaCard")?.scrollIntoView({ behavior: "smooth", block: "center" });
    });
  }
  if ($("btnOtaCheck")) {
    $("btnOtaCheck").addEventListener("click", async () => {
      try {
        const body = {
          repo: ($("otaRepo")?.value.trim()) || "Olegu621/OPENSTATION",
          branch: ($("otaBranch")?.value.trim()) || "main",
          autoCheck: $("otaAutoCheck") ? $("otaAutoCheck").checked : true
        };
        const r = await api("/api/ota/check", "POST", body);
        if (r.ota) applyOtaState(r.ota, state?.firmwareVersion);
        showToast(r.started ? "Проверка обновлений на GitHub..." : "Проверка уже выполняется");
      } catch (e) { showToast("Ошибка OTA: " + e.message, true); }
    });
  }
  async function triggerOtaInstall(force) {
    const ver = state?.ota?.latestVersion || state?.firmwareVersion || "1.2.1";
    const msg = force
      ? `Скачать и переустановить OpenStation Max v${ver} с GitHub? Проверяется SHA-256, настройки сохранятся.`
      : `Установить обновление OpenStation Max v${ver} с GitHub? Проверяется SHA-256, настройки сохранятся.`;
    if (!confirm(msg)) return;
    otaPendingRestart = true;
    try {
      const r = await api("/api/ota/install", "POST", { force: !!force });
      if (r.ota) applyOtaState(r.ota, state?.firmwareVersion);
      showToast(r.message || "Запуск обновления OTA...", !r.ok);
    } catch (e) {
      otaPendingRestart = false;
      showToast("Ошибка OTA: " + e.message, true);
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
      const l = await api("/api/logs");
      (l.logs || []).forEach(appendLog);
      loadApps();
    } catch (e) {
      showToast("Нет связи со станцией, переподключение...", true);
    }
    connectWs();
  }

  init();
})();
