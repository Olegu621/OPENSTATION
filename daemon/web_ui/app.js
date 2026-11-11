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
  let toastTimer = null;
  let lastHistSig = "";

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
        wooferVolume: parseInt($("wooferVol").value, 10),
        tweetersVolume: parseInt($("tweetersVol").value, 10),
        headphoneVolume: parseInt($("hpVol").value, 10),
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
        kwsThreads: parseInt($("astKwsThreads").value, 10) || 2,
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
    $("alsaDump").textContent = JSON.stringify(mixer, null, 2);

    $("wyoStatusBanner").textContent = wyo.clientConnected
      ? `Home Assistant подключен (${wyo.peerIp})${wyo.streaming ? " • СТРИМИНГ" : ""}${wyo.lastTranscript ? " • Текст: " + wyo.lastTranscript : ""}`
      : `Порт ${wyo.port || 10700} открыт • Ожидание подключения Home Assistant`;
    $("wyoStatusBanner").className = "status-banner " + (wyo.clientConnected ? "ok" : "warn");

    $("mqttStatusBanner").textContent = mq.enabled ? (mq.connected ? `Подключен к ${mq.host}:${mq.port}` : `Подключение к ${mq.host}:${mq.port}...`) : "Отключено";
    $("mqttStatusBanner").className = "status-banner " + (mq.connected ? "ok" : "");

    applyAliceBanner(ast.state || "idle", ast, cfg.assistant);
    applyMusicState(d.music || {});
    updateAliceLiveBox(ast.liveUserText || "", ast.liveModelText || "");
    if (Array.isArray(ast.history)) {
      renderAliceHistory(ast.history);
    }

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
      $("wooferVol").value = cfg.audio?.wooferVolume ?? 169;
      $("lblWooferVol").textContent = $("wooferVol").value;
      $("tweetersVol").value = cfg.audio?.tweetersVolume ?? 169;
      $("lblTweetersVol").textContent = $("tweetersVol").value;
      $("hpVol").value = cfg.audio?.headphoneVolume ?? 180;
      $("lblHpVol").textContent = $("hpVol").value;
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
      $("captureMode").value = cfg.audio?.captureMode || "aaudio";
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
        $("astKwsThreads").value = cfg.assistant.kwsThreads ?? 2;
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
    ["wooferVol", "lblWooferVol"], ["tweetersVol", "lblTweetersVol"], ["hpVol", "lblHpVol"], ["pdmGain", "lblPdmGain"]
  ];
  bindIds.forEach(([id, lbl]) => {
    $(id).addEventListener("input", () => {
      $(lbl).textContent = $(id).value;
      scheduleConfigPush();
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
   "astKwsThreads", "astChime", "astSearch", "astEchoGuard", "astPreRoll", "astFollowUp", "astMaxSess", "astVadSilence"].forEach((id) => {
    $(id).addEventListener("change", scheduleConfigPush);
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
          else if (msg.type === "assistant_event") {
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

  async function init() {
    try {
      const s = await api("/api/status");
      applyTelemetry(s, true);
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
