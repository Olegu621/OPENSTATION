#!/usr/bin/env python3
"""
Extracts YandexMusicMod-XLam (Electron Next.js export + XlamModz) from app.asar,
down-levels JS (via esbuild --target=chrome66) and CSS (@layer, oklch, logical inset)
for Android 9 Pie WebView compatibility, injects _ym_bridge.js / _ym_tv.css,
and packages everything into assets/ym_app.tar.gz.
"""

import io
import json
import math
import os
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile

DEFAULT_ASAR = r"C:\Users\clash\AppData\Local\Programs\YandexMusicMod-XLam\resources\app.asar"
ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_TAR_GZ = os.path.join(ROOT_DIR, "assets", "ym_app.tar.gz")

ESBUILD_JS_CANDIDATES = [
    r"C:\Users\clash\.gemini\antigravity\brain\e3990465-43ea-48b9-ad53-b1f2ec965e82\scratch\tools\node_modules\esbuild\lib\main.js",
    os.path.join(ROOT_DIR, "node_modules", "esbuild", "lib", "main.js"),
]
NODE_EXE = r"C:\Program Files\nodejs\node.exe"

YM_BRIDGE_JS = r"""(function () {
  "use strict";

  // 1. Global / ES2019-ES2024 polyfills for Android 9 Stock WebView (Chrome 66+)
  var g = typeof window !== "undefined" ? window : self;
  if (typeof globalThis === "undefined") {
    try { g.globalThis = g; } catch (e) {}
  }
  if (typeof g.BigInt === "undefined") {
    g.BigInt = function (v) {
      var n = Number(v);
      return isFinite(n) ? n : 0;
    };
  }
  if (typeof g.queueMicrotask === "undefined") {
    g.queueMicrotask = function (cb) {
      Promise.resolve().then(cb);
    };
  }
  if (!String.prototype.replaceAll) {
    String.prototype.replaceAll = function (search, replacement) {
      if (search instanceof RegExp) {
        if (!search.global) {
          throw new TypeError("replaceAll called with a non-global RegExp");
        }
        return this.replace(search, replacement);
      }
      var s = String(search);
      if (s === "") {
        return this.split("").join(String(replacement));
      }
      return this.split(s).join(typeof replacement === "function"
        ? replacement
        : String(replacement));
    };
  }
  if (!String.prototype.matchAll) {
    String.prototype.matchAll = function (rx) {
      var flags = rx.flags || "";
      if (flags.indexOf("g") < 0) flags += "g";
      var re = new RegExp(rx.source, flags);
      var str = String(this);
      var arr = [];
      var m;
      while ((m = re.exec(str)) !== null) {
        arr.push(m);
        if (m[0] === "") re.lastIndex++;
      }
      return arr[Symbol.iterator]();
    };
  }
  if (!String.prototype.at) {
    String.prototype.at = function (n) {
      var idx = Math.trunc(n) || 0;
      if (idx < 0) idx += this.length;
      if (idx < 0 || idx >= this.length) return undefined;
      return this.charAt(idx);
    };
  }
  if (!Array.prototype.at) {
    Array.prototype.at = function (n) {
      var idx = Math.trunc(n) || 0;
      if (idx < 0) idx += this.length;
      if (idx < 0 || idx >= this.length) return undefined;
      return this[idx];
    };
  }
  [Int8Array, Uint8Array, Uint16Array, Int16Array, Uint32Array, Int32Array, Float32Array, Float64Array].forEach(function (TA) {
    if (TA && TA.prototype && !TA.prototype.at) {
      TA.prototype.at = Array.prototype.at;
    }
  });
  if (!Array.prototype.flat) {
    Array.prototype.flat = function (depth) {
      var d = depth === undefined ? 1 : Number(depth);
      var out = [];
      (function flatten(arr, curD) {
        for (var i = 0; i < arr.length; i++) {
          if (Array.isArray(arr[i]) && curD > 0) flatten(arr[i], curD - 1);
          else out.push(arr[i]);
        }
      })(this, d);
      return out;
    };
  }
  if (!Array.prototype.flatMap) {
    Array.prototype.flatMap = function (fn, thisArg) {
      return this.map(fn, thisArg).flat(1);
    };
  }
  if (!Object.fromEntries) {
    Object.fromEntries = function (entries) {
      var obj = {};
      if (!entries) return obj;
      if (Array.isArray(entries)) {
        for (var i = 0; i < entries.length; i++) {
          var p = entries[i];
          if (p) obj[p[0]] = p[1];
        }
      } else if (typeof entries[Symbol.iterator] === "function") {
        var it = entries[Symbol.iterator]();
        var step;
        while (!(step = it.next()).done) {
          if (step.value) obj[step.value[0]] = step.value[1];
        }
      }
      return obj;
    };
  }
  if (!Object.hasOwn) {
    Object.hasOwn = function (obj, prop) {
      return Object.prototype.hasOwnProperty.call(obj, prop);
    };
  }
  if (!Promise.allSettled) {
    Promise.allSettled = function (promises) {
      return Promise.all(Array.from(promises).map(function (p) {
        return Promise.resolve(p).then(
          function (value) { return { status: "fulfilled", value: value }; },
          function (reason) { return { status: "rejected", reason: reason }; }
        );
      }));
    };
  }
  if (!Promise.any) {
    Promise.any = function (promises) {
      var arr = Array.from(promises);
      if (!arr.length) return Promise.reject(new Error("AggregateError: All promises were rejected"));
      return new Promise(function (resolve, reject) {
        var errs = new Array(arr.length);
        var rem = arr.length;
        arr.forEach(function (p, i) {
          Promise.resolve(p).then(resolve, function (e) {
            errs[i] = e;
            rem--;
            if (rem === 0) reject(new Error("AggregateError: All promises were rejected"));
          });
        });
      });
    };
  }
  if (typeof g.structuredClone !== "function") {
    g.structuredClone = function (val) {
      if (val === null || typeof val !== "object") return val;
      if (val instanceof Date) return new Date(val.getTime());
      if (val instanceof RegExp) return new RegExp(val.source, val.flags);
      if (val instanceof ArrayBuffer) return val.slice(0);
      if (ArrayBuffer.isView(val)) return new val.constructor(val);
      if (Array.isArray(val)) return val.map(g.structuredClone);
      var copy = {};
      Object.keys(val).forEach(function (k) { copy[k] = g.structuredClone(val[k]); });
      return copy;
    };
  }
  function makeUuidV4() {
    var b = new Uint8Array(16);
    if (g.crypto && g.crypto.getRandomValues) {
      g.crypto.getRandomValues(b);
    } else {
      for (var i = 0; i < 16; i++) b[i] = (Math.random() * 256) | 0;
    }
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    var h = [];
    for (var j = 0; j < 16; j++) h.push((b[j] + 0x100).toString(16).slice(1));
    return h.slice(0, 4).join("") + "-" + h.slice(4, 6).join("") + "-" + h.slice(6, 8).join("") + "-" + h.slice(8, 10).join("") + "-" + h.slice(10, 16).join("");
  }
  if (g.crypto && !g.crypto.randomUUID) {
    try { g.crypto.randomUUID = makeUuidV4; } catch (e) {}
  }
  if (typeof g.ResizeObserver === "undefined") {
    g.ResizeObserver = function (cb) {
      this._cb = cb;
      this.observe = function () {};
      this.unobserve = function () {};
      this.disconnect = function () {};
    };
  }
  if (typeof Element !== "undefined" && !Element.prototype.replaceChildren) {
    Element.prototype.replaceChildren = function () {
      while (this.firstChild) this.removeChild(this.firstChild);
      for (var i = 0; i < arguments.length; i++) {
        var n = arguments[i];
        this.appendChild(typeof n === "string" ? document.createTextNode(n) : n);
      }
    };
  }
  if (typeof Element !== "undefined" && !Element.prototype.toggleAttribute) {
    Element.prototype.toggleAttribute = function (name, force) {
      var has = this.hasAttribute(name);
      if (force === undefined) force = !has;
      if (force) {
        if (!has) this.setAttribute(name, "");
        return true;
      }
      this.removeAttribute(name);
      return false;
    };
  }
  if (typeof Element !== "undefined" && !Element.prototype.checkVisibility) {
    Element.prototype.checkVisibility = function () {
      if (!this.isConnected) return false;
      var st = g.getComputedStyle ? g.getComputedStyle(this) : null;
      if (st && (st.display === "none" || st.visibility === "hidden" || st.visibility === "collapse")) return false;
      return !!(this.offsetWidth || this.offsetHeight || this.getClientRects().length);
    };
  }
  if (typeof HTMLFormElement !== "undefined" && !HTMLFormElement.prototype.requestSubmit) {
    HTMLFormElement.prototype.requestSubmit = function (submitter) {
      if (submitter) {
        submitter.click();
      } else {
        var ev = new Event("submit", { bubbles: true, cancelable: true });
        if (this.dispatchEvent(ev)) this.submit();
      }
    };
  }
  if (!Array.prototype.findLast) {
    Array.prototype.findLast = function (fn, thisArg) {
      for (var i = this.length - 1; i >= 0; i--) {
        if (fn.call(thisArg, this[i], i, this)) return this[i];
      }
      return undefined;
    };
  }
  if (!Array.prototype.findLastIndex) {
    Array.prototype.findLastIndex = function (fn, thisArg) {
      for (var i = this.length - 1; i >= 0; i--) {
        if (fn.call(thisArg, this[i], i, this)) return i;
      }
      return -1;
    };
  }
  if (!Array.prototype.toReversed) {
    Array.prototype.toReversed = function () { return this.slice().reverse(); };
  }
  if (!Array.prototype.toSorted) {
    Array.prototype.toSorted = function (cmp) { return this.slice().sort(cmp); };
  }
  if (!Array.prototype.toSpliced) {
    Array.prototype.toSpliced = function () {
      var copy = this.slice();
      Array.prototype.splice.apply(copy, arguments);
      return copy;
    };
  }
  if (!Array.prototype.with) {
    Array.prototype.with = function (idx, val) {
      var copy = this.slice();
      var i = Math.trunc(idx) || 0;
      if (i < 0) i += copy.length;
      if (i < 0 || i >= copy.length) throw new RangeError("Invalid index");
      copy[i] = val;
      return copy;
    };
  }
  [Int8Array, Uint8Array, Uint16Array, Int16Array, Uint32Array, Int32Array, Float32Array, Float64Array].forEach(function (TA) {
    if (TA && TA.prototype) {
      if (!TA.prototype.findLast) TA.prototype.findLast = Array.prototype.findLast;
      if (!TA.prototype.findLastIndex) TA.prototype.findLastIndex = Array.prototype.findLastIndex;
    }
  });
  if (!Object.groupBy) {
    Object.groupBy = function (items, cb) {
      var res = Object.create(null);
      var i = 0;
      for (var it of items) {
        var k = cb(it, i++);
        if (!res[k]) res[k] = [];
        res[k].push(it);
      }
      return res;
    };
  }
  if (typeof Map !== "undefined" && !Map.groupBy) {
    Map.groupBy = function (items, cb) {
      var res = new Map();
      var i = 0;
      for (var it of items) {
        var k = cb(it, i++);
        var arr = res.get(k);
        if (!arr) { arr = []; res.set(k, arr); }
        arr.push(it);
      }
      return res;
    };
  }
  if (!Promise.withResolvers) {
    Promise.withResolvers = function () {
      var resolve, reject;
      var promise = new Promise(function (res, rej) { resolve = res; reject = rej; });
      return { promise: promise, resolve: resolve, reject: reject };
    };
  }
  if (typeof Blob !== "undefined" && !Blob.prototype.arrayBuffer) {
    Blob.prototype.arrayBuffer = function () {
      var selfBlob = this;
      return new Promise(function (resolve, reject) {
        var fr = new FileReader();
        fr.onload = function () { resolve(fr.result); };
        fr.onerror = function () { reject(fr.error || new Error("Blob read error")); };
        fr.readAsArrayBuffer(selfBlob);
      });
    };
  }
  if (typeof Blob !== "undefined" && !Blob.prototype.text) {
    Blob.prototype.text = function () {
      var selfBlob = this;
      return new Promise(function (resolve, reject) {
        var fr = new FileReader();
        fr.onload = function () { resolve(String(fr.result || "")); };
        fr.onerror = function () { reject(fr.error || new Error("Blob read error")); };
        fr.readAsText(selfBlob);
      });
    };
  }
  if (typeof Blob !== "undefined" && !Blob.prototype.stream && typeof ReadableStream !== "undefined") {
    Blob.prototype.stream = function () {
      var selfBlob = this;
      return new ReadableStream({
        start: function (controller) {
          selfBlob.arrayBuffer().then(function (ab) {
            controller.enqueue(new Uint8Array(ab));
            controller.close();
          }, function (err) {
            controller.error(err);
          });
        }
      });
    };
  }
  if (typeof g.AbortController === "undefined") {
    g.AbortController = function () {
      var sig = {
        aborted: false,
        onabort: null,
        _ls: [],
        addEventListener: function (t, fn) { if (t === "abort" && typeof fn === "function") sig._ls.push(fn); },
        removeEventListener: function (t, fn) { sig._ls = sig._ls.filter(function (x) { return x !== fn; }); }
      };
      this.signal = sig;
      this.abort = function (reason) {
        if (sig.aborted) return;
        sig.aborted = true;
        sig.reason = reason;
        if (typeof sig.onabort === "function") try { sig.onabort({ type: "abort" }); } catch (e) {}
        sig._ls.slice().forEach(function (fn) { try { fn({ type: "abort" }); } catch (e) {} });
      };
    };
  }
  if (typeof g.AbortSignal !== "undefined") {
    if (!g.AbortSignal.timeout) {
      g.AbortSignal.timeout = function (ms) {
        var c = new g.AbortController();
        setTimeout(function () { c.abort(new Error("TimeoutError")); }, Number(ms) || 0);
        return c.signal;
      };
    }
    if (!g.AbortSignal.any) {
      g.AbortSignal.any = function (signals) {
        var c = new g.AbortController();
        var arr = Array.from(signals || []);
        for (var i = 0; i < arr.length; i++) {
          var s = arr[i];
          if (!s) continue;
          if (s.aborted) { c.abort(s.reason); return c.signal; }
          if (s.addEventListener) s.addEventListener("abort", function () { c.abort(this.reason); }, { once: true });
        }
        return c.signal;
      };
    }
  }
  if (typeof g.TransformStream === "undefined" && typeof ReadableStream !== "undefined") {
    g.TransformStream = function (transformer) {
      var ctrl = null;
      var readable = new ReadableStream({
        start: function (c) { ctrl = c; }
      });
      var writable = typeof WritableStream !== "undefined" ? new WritableStream({
        write: function (chunk) {
          if (transformer && typeof transformer.transform === "function") {
            return transformer.transform(chunk, { enqueue: function (d) { ctrl && ctrl.enqueue(d); } });
          }
          ctrl && ctrl.enqueue(chunk);
        },
        close: function () {
          if (transformer && typeof transformer.flush === "function") {
            return Promise.resolve(transformer.flush({ enqueue: function (d) { ctrl && ctrl.enqueue(d); } })).then(function () { ctrl && ctrl.close(); });
          }
          ctrl && ctrl.close();
        }
      }) : null;
      this.readable = readable;
      this.writable = writable;
    };
  }
  if (typeof g.PerformanceObserver !== "undefined" && g.PerformanceObserver.prototype && g.PerformanceObserver.prototype.observe) {
    var origPoObserve = g.PerformanceObserver.prototype.observe;
    g.PerformanceObserver.prototype.observe = function (options) {
      try {
        if (options && !options.entryTypes && options.type) {
          var fixed = Object.assign({}, options, { entryTypes: [options.type] });
          delete fixed.type;
          delete fixed.buffered;
          return origPoObserve.call(this, fixed);
        }
        return origPoObserve.call(this, options);
      } catch (e) {
        return undefined;
      }
    };
  }
  if (typeof g.matchMedia === "function") {
    var origMatchMedia = g.matchMedia.bind(g);
    g.matchMedia = function (query) {
      var mql = origMatchMedia(query);
      if (typeof query === "string" && /prefers-color-scheme\s*:\s*dark/i.test(query)) {
        try {
          return {
            matches: true,
            media: query,
            onchange: null,
            addListener: function () {},
            removeListener: function () {},
            addEventListener: function () {},
            removeEventListener: function () {},
            dispatchEvent: function () { return true; }
          };
        } catch (e) {}
      }
      return mql;
    };
  }
  if (typeof Intl !== "undefined") {
    if (!Intl.RelativeTimeFormat) {
      Intl.RelativeTimeFormat = function () {
        this.format = function (v, u) { return String(v) + " " + String(u || ""); };
        this.formatToParts = function (v, u) { return [{ type: "literal", value: this.format(v, u) }]; };
      };
    }
    if (!Intl.ListFormat) {
      Intl.ListFormat = function () {
        this.format = function (arr) { return Array.isArray(arr) ? arr.join(", ") : String(arr || ""); };
      };
    }
    if (!Intl.DisplayNames) {
      Intl.DisplayNames = function (locales, opts) {
        this._opts = opts || {};
        this.of = function (code) { return String(code || ""); };
        this.resolvedOptions = function () { return { locale: String(locales || "ru"), style: "long", type: this._opts.type || "language", fallback: "code" }; };
      };
      Intl.DisplayNames.supportedLocalesOf = function (locs) { return Array.isArray(locs) ? locs.slice() : [String(locs || "ru")]; };
    }
  }
  // Virtual in-memory OPFS (navigator.storage.getDirectory) for Chrome 66
  (function installVirtualOpfs() {
    if (typeof navigator === "undefined") return;
    if (!navigator.storage) {
      try { Object.defineProperty(navigator, "storage", { value: {}, configurable: true }); } catch (e) {}
    }
    if (!navigator.storage) return;
    if (!navigator.storage.estimate) {
      try {
        Object.defineProperty(navigator.storage, "estimate", {
          value: function () { return Promise.resolve({ quota: 268435456, usage: 0 }); },
          configurable: true
        });
      } catch (e) {}
    }
    if (typeof navigator.storage.getDirectory === "function") return;

    function toUint8(data) {
      if (data == null) return Promise.resolve(new Uint8Array(0));
      if (typeof data === "string") {
        if (typeof TextEncoder !== "undefined") return Promise.resolve(new TextEncoder().encode(data));
        var arr = new Uint8Array(data.length);
        for (var i = 0; i < data.length; i++) arr[i] = data.charCodeAt(i) & 0xff;
        return Promise.resolve(arr);
      }
      if (data instanceof Uint8Array) return Promise.resolve(data.slice());
      if (data instanceof ArrayBuffer) return Promise.resolve(new Uint8Array(data.slice(0)));
      if (ArrayBuffer.isView(data)) return Promise.resolve(new Uint8Array(data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength)));
      if (typeof Blob !== "undefined" && data instanceof Blob) {
        return data.arrayBuffer().then(function (ab) { return new Uint8Array(ab); });
      }
      if (typeof data === "object" && data.data !== undefined) return toUint8(data.data);
      return Promise.resolve(new Uint8Array(0));
    }

    function notFoundErr(msg) {
      var err = new Error(msg || "NotFoundError");
      err.name = "NotFoundError";
      return err;
    }

    function VFileHandle(name) {
      this.kind = "file";
      this.name = name;
      this._bytes = new Uint8Array(0);
      this._mtime = Date.now();
    }
    VFileHandle.prototype.getFile = function () {
      var b = this._bytes;
      try {
        return Promise.resolve(new File([b], this.name, { type: "application/octet-stream", lastModified: this._mtime }));
      } catch (e) {
        var blob = new Blob([b], { type: "application/octet-stream" });
        blob.name = this.name;
        blob.lastModified = this._mtime;
        return Promise.resolve(blob);
      }
    };
    VFileHandle.prototype.createWritable = function () {
      var selfFile = this;
      var chunks = [];
      return Promise.resolve({
        write: function (data) {
          return toUint8(data).then(function (u8) { chunks.push(u8); });
        },
        truncate: function () { chunks = []; return Promise.resolve(); },
        close: function () {
          var total = 0;
          for (var i = 0; i < chunks.length; i++) total += chunks[i].byteLength;
          var merged = new Uint8Array(total);
          var off = 0;
          for (var j = 0; j < chunks.length; j++) {
            merged.set(chunks[j], off);
            off += chunks[j].byteLength;
          }
          selfFile._bytes = merged;
          selfFile._mtime = Date.now();
          return Promise.resolve();
        },
        abort: function () { chunks = []; return Promise.resolve(); }
      });
    };

    function VDirHandle(name) {
      this.kind = "directory";
      this.name = name || "";
      this._entries = Object.create(null);
    }
    VDirHandle.prototype.getDirectoryHandle = function (name, opts) {
      var key = String(name || "");
      var existing = this._entries[key];
      if (existing) {
        if (existing.kind !== "directory") return Promise.reject(new Error("TypeMismatchError"));
        return Promise.resolve(existing);
      }
      if (opts && opts.create) {
        var d = new VDirHandle(key);
        this._entries[key] = d;
        return Promise.resolve(d);
      }
      return Promise.reject(notFoundErr("Directory not found: " + key));
    };
    VDirHandle.prototype.getFileHandle = function (name, opts) {
      var key = String(name || "");
      var existing = this._entries[key];
      if (existing) {
        if (existing.kind !== "file") return Promise.reject(new Error("TypeMismatchError"));
        return Promise.resolve(existing);
      }
      if (opts && opts.create) {
        var f = new VFileHandle(key);
        this._entries[key] = f;
        return Promise.resolve(f);
      }
      return Promise.reject(notFoundErr("File not found: " + key));
    };
    VDirHandle.prototype.removeEntry = function (name) {
      var key = String(name || "");
      if (!this._entries[key]) return Promise.reject(notFoundErr("Entry not found: " + key));
      delete this._entries[key];
      return Promise.resolve();
    };
    VDirHandle.prototype.values = function () {
      var vals = Object.keys(this._entries).map(function (k) { return this._entries[k]; }, this);
      var idx = 0;
      return {
        next: function () { return Promise.resolve(idx < vals.length ? { value: vals[idx++], done: false } : { value: undefined, done: true }); }
      };
    };

    var rootDir = new VDirHandle("");
    try {
      Object.defineProperty(navigator.storage, "getDirectory", {
        value: function () { return Promise.resolve(rootDir); },
        configurable: true
      });
    } catch (e) {}
  })();

  // MediaMetadata + navigator.mediaSession polyfill for Chrome 66
  var msHandlers = Object.create(null);
  function emitMediaMetadata(meta) {
    try {
      if (!meta || !meta.title) return;
      if (g.StationNative && g.StationNative.onNowPlaying) {
        g.StationNative.onNowPlaying(meta.title || "", meta.artist || "", meta.album || "");
      }
    } catch (e) {}
  }
  if (typeof g.MediaMetadata === "undefined") {
    g.MediaMetadata = function (init) {
      init = init || {};
      this.title = init.title || "";
      this.artist = init.artist || "";
      this.album = init.album || "";
      this.artwork = Array.isArray(init.artwork) ? init.artwork.slice() : [];
    };
  }
  if (typeof navigator !== "undefined" && !("mediaSession" in navigator)) {
    var msMeta = null;
    var msPlayback = "none";
    try {
      Object.defineProperty(navigator, "mediaSession", {
        configurable: true,
        value: {
          get metadata() { return msMeta; },
          set metadata(v) { msMeta = v || null; emitMediaMetadata(msMeta); },
          get playbackState() { return msPlayback; },
          set playbackState(v) { msPlayback = v || "none"; },
          setActionHandler: function (action, fn) {
            if (typeof fn === "function") msHandlers[action] = fn;
            else delete msHandlers[action];
          },
          setPositionState: function () {},
          setCameraActive: function () {},
          setMicrophoneActive: function () {}
        }
      });
    } catch (e) {}
  }

  // 2. Stable Electron / Yandex Music Preload Globals
  var devInfo = null;
  try {
    var saved = localStorage.getItem("_ym_device_info");
    if (saved) devInfo = JSON.parse(saved);
  } catch (e) {}
  if (!devInfo || !devInfo.uuid || !devInfo.device_id) {
    var u = makeUuidV4();
    var d = makeUuidV4().replace(/-/g, "") + makeUuidV4().replace(/-/g, "");
    devInfo = {
      manufacturer: "OpenStation",
      model: "StationMax",
      uuid: u,
      os: "win32",
      os_version: "10.0.19045",
      device_id: d,
      clid: 0
    };
    try { localStorage.setItem("_ym_device_info", JSON.stringify(devInfo)); } catch (e) {}
  }

  g.VERSION = "5.86.0";
  g.BRANCH = "r18727399";
  g.PLATFORM = "win32";
  g.DEVICE_INFO = devInfo;
  g.DEVICE_HOSTNAME = "OpenStationMax";

  // 3. Proxy URL & Ad/Telemetry Filter (sits UNDERNEATH xlamModz/renderer.js so PT() still sees https://api.music.yandex.net!)
  var BLOCK_SUBSTRS = [
    "yandex.ru/clck",
    "mc.yandex.ru",
    "api.music.yandex.net/dynamic-pages/trigger",
    "api.music.yandex.net/lyric-views",
    "log.strm.yandex.ru",
    "events.plus.yandex.net",
    "plus.yandex.net/",
    "yandex.ru/ads",
    "strm.yandex.ru/ping"
  ];
  function isBlockedUrl(u) {
    if (!u) return false;
    var s = String(u);
    for (var i = 0; i < BLOCK_SUBSTRS.length; i++) {
      if (s.indexOf(BLOCK_SUBSTRS[i]) >= 0) return true;
    }
    return false;
  }
  function isLocalOwnOrigin(u) {
    var s = String(u || "");
    if (s.indexOf(location.origin) === 0) return true;
    if (/^https?:\/\/(127\.0\.0\.1|localhost)(:\d+)?\//i.test(s)) return true;
    return false;
  }
  function toProxyUrl(u) {
    var s = String(u || "");
    if (!s) return s;
    if (/^(data:|blob:|about:|javascript:)/i.test(s)) return s;
    if (s.indexOf("/_ym_proxy?") === 0) return s;
    if (/^https?:\/\//i.test(s) && !isLocalOwnOrigin(s)) {
      return location.origin + "/_ym_proxy?url=" + encodeURIComponent(s);
    }
    return s;
  }

  var origFetch = g.fetch.bind(g);
  g.__ymOrigFetch = origFetch;

  g.fetch = async function (input, init) {
    var url = "";
    var isReq = typeof Request !== "undefined" && input instanceof Request;
    if (isReq) url = input.url;
    else if (input && typeof input === "object" && input.url) url = input.url;
    else url = String(input);

    if (isBlockedUrl(url)) {
      return new Response("", { status: 204, statusText: "No Content" });
    }

    if (!/^https?:\/\//i.test(url) || isLocalOwnOrigin(url)) {
      return origFetch(input, init);
    }

    var method = (init && init.method) || (isReq ? input.method : "GET") || "GET";
    var headers = new Headers(isReq ? input.headers : undefined);
    if (init && init.headers) {
      new Headers(init.headers).forEach(function (v, k) { headers.set(k, v); });
    }
    headers.delete("x-yandex-music-device");
    headers.delete("x-request-id");

    var body = init && init.body !== undefined ? init.body : undefined;
    if (body === undefined && isReq && method !== "GET" && method !== "HEAD") {
      try {
        var ab = await input.clone().arrayBuffer();
        if (ab && ab.byteLength > 0) body = ab;
      } catch (e) {}
    }

    var opts = {
      method: method,
      headers: headers,
      mode: "cors",
      credentials: "same-origin"
    };
    if (body !== undefined && method !== "GET" && method !== "HEAD") {
      opts.body = body;
    }
    if (init && init.signal) opts.signal = init.signal;
    else if (isReq && input.signal) opts.signal = input.signal;

    return origFetch(toProxyUrl(url), opts);
  };

  if (typeof XMLHttpRequest !== "undefined") {
    var origOpen = XMLHttpRequest.prototype.open;
    var origSetHdr = XMLHttpRequest.prototype.setRequestHeader;
    var FORBIDDEN_XHR = {
      "user-agent": 1,
      "origin": 1,
      "referer": 1,
      "cookie": 1,
      "host": 1,
      "content-length": 1,
      "x-yandex-music-device": 1,
      "x-request-id": 1
    };
    XMLHttpRequest.prototype.open = function (method, url) {
      var u = String(url || "");
      if (isBlockedUrl(u)) {
        u = location.origin + "/_ym_blocked";
      } else if (/^https?:\/\//i.test(u) && !isLocalOwnOrigin(u)) {
        u = toProxyUrl(u);
      }
      var args = Array.prototype.slice.call(arguments);
      args[1] = u;
      return origOpen.apply(this, args);
    };
    XMLHttpRequest.prototype.setRequestHeader = function (name, value) {
      var low = String(name || "").toLowerCase();
      if (FORBIDDEN_XHR[low]) return;
      try {
        return origSetHdr.call(this, name, value);
      } catch (e) {}
    };
  }

  // 4. desktopEvents IPC Bridge
  var ipcListeners = Object.create(null);
  function emitIpc(channel) {
    var args = Array.prototype.slice.call(arguments, 1);
    var arr = ipcListeners[channel];
    if (!arr || !arr.length) return;
    arr.slice().forEach(function (fn) {
      try { fn.apply(null, args); } catch (e) { console.error("[YM Bridge] listener err", channel, e); }
    });
  }

  function hasStoredOAuth() {
    try {
      var raw = localStorage.getItem("oauth");
      if (!raw) return false;
      var parsed = JSON.parse(raw);
      return !!(parsed && parsed.value);
    } catch (e) {
      return false;
    }
  }

  g.desktopEvents = {
    send: function (name) {
      var args = Array.prototype.slice.call(arguments, 1);
      if (name === "PLAYER_STATE" && args[0] && g.StationNative && g.StationNative.onPlayerState) {
        try { g.StationNative.onPlayerState(JSON.stringify(args[0])); } catch (e) {}
      } else if (name === "TRACKS_AVAILABILITY_UPDATED") {
        try { localStorage.setItem("_ym_tracks_avail_at", String(Date.now())); } catch (e) {}
      } else if (name === "REPOSITORY_META_UPDATED") {
        try { localStorage.setItem("_ym_repo_meta_at", String(Date.now())); } catch (e) {}
      }
    },
    on: function (name, fn) {
      if (typeof fn !== "function") return;
      if (!ipcListeners[name]) ipcListeners[name] = [];
      if (ipcListeners[name].indexOf(fn) < 0) ipcListeners[name].push(fn);
    },
    off: function (name, fn) {
      var arr = ipcListeners[name];
      if (!arr) return;
      ipcListeners[name] = arr.filter(function (f) { return f !== fn; });
    },
    invoke: async function (name) {
      if (name === "GET_PASSPORT_LOGIN") {
        try {
          if (g.StationNative && g.StationNative.getPassportLogin) {
            var login = g.StationNative.getPassportLogin();
            if (login) return login;
          }
        } catch (e) {}
        return hasStoredOAuth() ? "openstation" : undefined;
      }
      if (name === "GET_YANDEX_UID") {
        try {
          if (g.StationNative && g.StationNative.getYandexUid) {
            var uid = g.StationNative.getYandexUid();
            if (uid) return uid;
          }
        } catch (e) {}
        var cachedUid = localStorage.getItem("_ym_fallback_uid");
        if (!cachedUid) {
          cachedUid = String(100000000 + Math.floor(Math.random() * 899999999));
          try { localStorage.setItem("_ym_fallback_uid", cachedUid); } catch (e) {}
        }
        return cachedUid;
      }
      return undefined;
    }
  };

  // 5. xlamModz IPC Bridge + Storage Persistence
  var modListeners = [];
  function readModKey(key) {
    try {
      if (g.StationNative && g.StationNative.modGet) {
        var raw = g.StationNative.modGet(String(key));
        if (raw && raw !== "null") return JSON.parse(raw);
      }
    } catch (e) {}
    try {
      var ls = localStorage.getItem("_xm_" + key);
      if (ls !== null) return JSON.parse(ls);
    } catch (e) {}
    return null;
  }
  function writeModKey(key, value) {
    var ser = JSON.stringify(value === undefined ? null : value);
    try {
      if (g.StationNative && g.StationNative.modSet) {
        g.StationNative.modSet(String(key), ser);
      }
    } catch (e) {}
    try {
      localStorage.setItem("_xm_" + key, ser);
    } catch (e) {}
    modListeners.slice().forEach(function (cb) {
      try { cb(key, value); } catch (e) {}
    });
  }

  g.xlamModz = {
    getStorageValue: async function (key) {
      return readModKey(key);
    },
    setStorageValue: function (key, value) {
      writeModKey(key, value);
    },
    onStorageChanged: function (cb) {
      if (typeof cb !== "function") return function () {};
      modListeners.push(cb);
      return function () {
        modListeners = modListeners.filter(function (f) { return f !== cb; });
      };
    },
    downloadTrack: async function (downloadInfo, trackMeta) {
      try {
        if (!downloadInfo || !downloadInfo.url) return { ok: false, error: "No download URL" };
        var title = (trackMeta && trackMeta.title) ? trackMeta.title : "track";
        if (g.StationNative && g.StationNative.showToast) {
          g.StationNative.showToast("Загрузка пока недоступна на ТВ-приставке: " + title);
        }
        return { ok: false, error: "TV storage download disabled" };
      } catch (e) {
        return { ok: false, error: String(e) };
      }
    },
    openDownloadDirectory: function () {},
    selectDownloadFolder: async function () {
      return { success: false, canceled: true };
    },
    openFolder: async function () {
      return { success: false };
    },
    axios: async function (config) {
      config = config || {};
      var method = (config.method || "GET").toUpperCase();
      var fullUrl = config.url || "";
      if (config.baseURL && !/^https?:\/\//i.test(fullUrl)) {
        fullUrl = config.baseURL.replace(/\/+$/, "") + "/" + fullUrl.replace(/^\/+/, "");
      }
      if (config.params && typeof config.params === "object") {
        var qs = Object.keys(config.params).map(function (k) {
          return encodeURIComponent(k) + "=" + encodeURIComponent(String(config.params[k]));
        }).join("&");
        if (qs) fullUrl += (fullUrl.indexOf("?") >= 0 ? "&" : "?") + qs;
      }
      var headers = new Headers();
      if (config.headers) {
        Object.keys(config.headers).forEach(function (k) {
          var lk = k.toLowerCase();
          if (lk === "user-agent" || lk === "host" || lk === "content-length") return;
          if (config.headers[k] !== undefined && config.headers[k] !== null) {
            try { headers.set(k, String(config.headers[k])); } catch (e) {}
          }
        });
      }
      var fetchOpts = { method: method, headers: headers };
      if (config.data !== undefined && method !== "GET" && method !== "HEAD") {
        if (typeof config.data === "string" || config.data instanceof ArrayBuffer || ArrayBuffer.isView(config.data)) {
          fetchOpts.body = config.data;
        } else {
          if (!headers.has("Content-Type")) headers.set("Content-Type", "application/json");
          fetchOpts.body = JSON.stringify(config.data);
        }
      }
      var resp = await g.fetch(fullUrl, fetchOpts);
      var outHeaders = {};
      resp.headers.forEach(function (v, k) { outHeaders[k] = v; });
      var data;
      if (config.responseType === "arraybuffer") {
        data = await resp.arrayBuffer();
      } else if (config.responseType === "text") {
        data = await resp.text();
      } else {
        var txt = await resp.text();
        try { data = JSON.parse(txt); } catch (e) { data = txt; }
      }
      return {
        success: true,
        data: data,
        status: resp.status,
        statusText: resp.statusText,
        headers: outHeaders
      };
    }
  };

  // 6. TV Remote Controls + Now Playing Observer -> Android StationNative
  g.__ymRemoteAction = function (act) {
    // PlayerAction expects (ipcEvent, "PLAY" | "PAUSE" | "MOVE_BACKWARD" | "MOVE_FORWARD")
    if (ipcListeners["PLAYER_ACTION"] && ipcListeners["PLAYER_ACTION"].length) {
      emitIpc("PLAYER_ACTION", null, act);
      return;
    }
    var msMap = {
      "PLAY": (navigator.mediaSession && navigator.mediaSession.playbackState === "playing") ? "pause" : "play",
      "PAUSE": "pause",
      "MOVE_BACKWARD": "previoustrack",
      "MOVE_FORWARD": "nexttrack"
    };
    var msAct = msMap[act];
    if (msAct && typeof msHandlers[msAct] === "function") {
      try { msHandlers[msAct]({ action: msAct }); } catch (e) {}
    }
  };

  g.__ymSetOAuthToken = function (tok) {
    if (!tok) return false;
    var clean = String(tok).replace(/^OAuth\s+/i, "").trim();
    if (!clean) return false;
    var curYearMs = Date.now() + 365 * 24 * 3600 * 1000;
    try {
      localStorage.setItem("oauth", JSON.stringify({ value: clean, expires: curYearMs }));
      localStorage.setItem("oauthState", JSON.stringify({ value: "openstation", expires: curYearMs }));
    } catch (e) { return false; }
    return true;
  };

  var lastNowSig = "";
  setInterval(function () {
    try {
      var title = "";
      var artist = "";
      var album = "";
      if (navigator.mediaSession && navigator.mediaSession.metadata) {
        var m = navigator.mediaSession.metadata;
        title = m.title || "";
        artist = m.artist || "";
        album = m.album || "";
      }
      var sig = title + "|" + artist + "|" + album;
      if (title && sig !== lastNowSig) {
        lastNowSig = sig;
        if (g.StationNative && g.StationNative.onNowPlaying) {
          g.StationNative.onNowPlaying(title, artist, album);
        }
      }
    } catch (e) {}
  }, 1500);
})();
"""

YM_TV_CSS = """/* OpenStation TV overrides for YandexMusicMod-XLam */
#ym-title-bar, [data-test-id="WINDOW_CONTROLS"],
[class*="TitleBar_root__"], [class*="TitleBar_button__"], [class*="TitleBar_closeButton__"] {
  display: none !important;
}
:focus-visible, a:focus, button:focus, [tabindex]:focus {
  outline: 2px solid #00d4ff !important;
  outline-offset: 2px !important;
}
html, body {
  overscroll-behavior: none;
}
"""


def read_asar_index(fp):
    hdr_bytes = fp.read(16)
    _, hdr_size, _, json_size = struct.unpack("<IIII", hdr_bytes)
    hdr = json.loads(fp.read(json_size).decode("utf-8"))
    # Chromium Pickle: bytes 0..7 are size pickle (4, hdr_size); payload pickle starts at byte 8
    base_off = 8 + hdr_size
    return hdr, base_off


def walk_asar(node, prefix=""):
    out = []
    if "files" in node:
        for k, v in node["files"].items():
            p = f"{prefix}/{k}" if prefix else k
            out.extend(walk_asar(v, p))
    else:
        out.append((prefix, int(node.get("size", 0)), int(node.get("offset", 0)), bool(node.get("unpacked", False))))
    return out


# ---------------- OKLCH -> sRGB conversion ----------------

_OKLCH_RE = re.compile(
    r"oklch\(\s*([0-9.]+%?)\s+([0-9.]+)\s+([0-9.]+(?:deg|rad|turn|grad)?)\s*(?:/\s*([0-9.]+%?)\s*)?\)",
    re.I,
)


def _oklch_to_srgb_css(m):
    l_str, c_str, h_str, a_str = m.group(1), m.group(2), m.group(3), m.group(4)
    try:
        l_val = float(l_str[:-1]) / 100.0 if l_str.endswith("%") else float(l_str)
        c_val = float(c_str)
        hs = h_str.lower()
        if hs.endswith("rad"):
            h_rad = float(hs[:-3])
        elif hs.endswith("turn"):
            h_rad = float(hs[:-4]) * 2.0 * math.pi
        elif hs.endswith("grad"):
            h_rad = float(hs[:-4]) * math.pi / 200.0
        elif hs.endswith("deg"):
            h_rad = math.radians(float(hs[:-3]))
        else:
            h_rad = math.radians(float(hs))

        a_ok = c_val * math.cos(h_rad)
        b_ok = c_val * math.sin(h_rad)

        l_ = l_val + 0.3963377774 * a_ok + 0.2158037573 * b_ok
        m_ = l_val - 0.1055613458 * a_ok - 0.0638541728 * b_ok
        s_ = l_val - 0.0894841775 * a_ok - 1.2914855480 * b_ok

        l3 = l_ * l_ * l_
        m3 = m_ * m_ * m_
        s3 = s_ * s_ * s_

        r_lin = +4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3
        g_lin = -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3
        b_lin = -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3

        def to_srgb(x):
            x = max(0.0, min(1.0, x))
            if x <= 0.0031308:
                v = 12.92 * x
            else:
                v = 1.055 * (x ** (1.0 / 2.4)) - 0.055
            return max(0, min(255, int(round(v * 255))))

        r, g, b = to_srgb(r_lin), to_srgb(g_lin), to_srgb(b_lin)
        if a_str is not None:
            alpha = float(a_str[:-1]) / 100.0 if a_str.endswith("%") else float(a_str)
            alpha = max(0.0, min(1.0, alpha))
            return f"rgba({r},{g},{b},{alpha:.3f})"
        return f"rgb({r},{g},{b})"
    except Exception:
        return m.group(0)


def unwrap_css_layers(css: str) -> str:
    # Remove @layer order declarations like "@layer a, b, c;" and unwrap "@layer name { ... }"
    out = []
    i = 0
    n = len(css)
    while i < n:
        idx = css.find("@layer", i)
        if idx < 0:
            out.append(css[i:])
            break
        # Verify @layer isn't part of another word
        if idx > 0 and (css[idx - 1].isalnum() or css[idx - 1] in "-_"):
            out.append(css[i : idx + 6])
            i = idx + 6
            continue
        out.append(css[i:idx])
        semi = css.find(";", idx + 6)
        brace = css.find("{", idx + 6)
        if brace < 0 or (0 <= semi < brace):
            # Statement form: skip until ';'
            if semi < 0:
                break
            i = semi + 1
            continue
        # Block form: find matching closing brace and emit inner body
        depth = 0
        j = brace
        in_str = None
        while j < n:
            ch = css[j]
            if in_str:
                if ch == "\\" and j + 1 < n:
                    j += 2
                    continue
                if ch == in_str:
                    in_str = None
            else:
                if ch in ('"', "'"):
                    in_str = ch
                elif ch == "{":
                    depth += 1
                elif ch == "}":
                    depth -= 1
                    if depth == 0:
                        break
            j += 1
        inner = css[brace + 1 : j]
        # Recursively unwrap nested @layer if any
        out.append(unwrap_css_layers(inner))
        i = j + 1
    return "".join(out)


def _expand_inset_prop(m):
    prefix, val = m.group(1), m.group(2).strip()
    parts = val.split()
    if len(parts) == 1:
        t = r = b = l = parts[0]
    elif len(parts) == 2:
        t = b = parts[0]
        r = l = parts[1]
    elif len(parts) == 3:
        t, r, l, b = parts[0], parts[1], parts[1], parts[2]
    elif len(parts) == 4:
        t, r, b, l = parts[0], parts[1], parts[2], parts[3]
    else:
        return m.group(0)
    return f"{prefix}top:{t};right:{r};bottom:{b};left:{l}"


_INSET_RE = re.compile(r"([;{]\s*)inset\s*:\s*([^;{}]+)")
_INSET_BS_RE = re.compile(r"([;{]\s*)inset-block-start\s*:")
_INSET_BE_RE = re.compile(r"([;{]\s*)inset-block-end\s*:")
_INSET_IS_RE = re.compile(r"([;{]\s*)inset-inline-start\s*:")
_INSET_IE_RE = re.compile(r"([;{]\s*)inset-inline-end\s*:")
_PROPERTY_RE = re.compile(r"@property\s+(--[A-Za-z0-9_-]+)\s*\{([^}]*)\}")
_INIT_VAL_RE = re.compile(r"initial-value\s*:\s*([^;]+)")
_WHERE_IS_RE = re.compile(r":(?:where|is)\(([^(){}\[\]]+)\)")


def _extract_css_properties(css: str) -> str:
    defaults = []

    def _collect(m):
        prop_name = m.group(1).strip()
        body = m.group(2)
        im = _INIT_VAL_RE.search(body)
        if im:
            val = im.group(1).strip()
            if val:
                defaults.append(f"{prop_name}:{val}")
        return ""

    stripped = _PROPERTY_RE.sub(_collect, css)
    if defaults:
        return "*,::before,::after{" + ";".join(defaults) + ";}\n" + stripped
    return stripped


def _split_top_level_commas(s: str):
    parts = []
    depth = 0
    start = 0
    for idx, ch in enumerate(s):
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth = max(0, depth - 1)
        elif ch == "," and depth == 0:
            parts.append(s[start:idx].strip())
            start = idx + 1
    parts.append(s[start:].strip())
    return parts


def _strip_mix_percent(part: str) -> str:
    return re.sub(r"\s*[0-9]+(?:\.[0-9]+)?%\s*$", "", part).strip()


def _replace_color_mix(css: str) -> str:
    out = []
    i = 0
    low = css.lower()
    while True:
        idx = low.find("color-mix(", i)
        if idx < 0:
            out.append(css[i:])
            break
        out.append(css[i:idx])
        j = idx + len("color-mix(")
        depth = 1
        while j < len(css) and depth > 0:
            if css[j] == "(":
                depth += 1
            elif css[j] == ")":
                depth -= 1
            j += 1
        if depth != 0:
            out.append(css[idx:])
            break
        inner = css[idx + len("color-mix(") : j - 1]
        args = _split_top_level_commas(inner)
        if len(args) >= 3:
            c1 = _strip_mix_percent(args[1])
            c2 = _strip_mix_percent(args[2])
            if c1.lower() == "transparent" and c2:
                out.append(c2)
            elif c2.lower() == "transparent" and c1:
                out.append(c1)
            else:
                out.append(c1 or c2 or "currentColor")
        else:
            out.append(css[idx:j])
        i = j
    return "".join(out)


_DV_UNIT_RE = re.compile(r"(\d+(?:\.\d+)?)(?:d|s|l)v(w|h|min|max)\b", re.I)
_PREFERS_CS_RE = re.compile(r"@media\s*\(\s*prefers-color-scheme\s*:\s*(dark|light)\s*\)\s*\{", re.I)
_INSET_BOUNDARY_RE = re.compile(r"([;{\s])(?<![-_A-Za-z0-9])inset\s*:\s*([^;{}]+)")


def _unwrap_prefers_color_scheme(css: str) -> str:
    out = []
    i = 0
    n = len(css)
    while i < n:
        m = _PREFERS_CS_RE.search(css, i)
        if not m:
            out.append(css[i:])
            break
        out.append(css[i : m.start()])
        scheme = m.group(1).lower()
        brace = m.end() - 1
        depth = 0
        j = brace
        in_str = None
        while j < n:
            ch = css[j]
            if in_str:
                if ch == "\\" and j + 1 < n:
                    j += 2
                    continue
                if ch == in_str:
                    in_str = None
            else:
                if ch in ('"', "'"):
                    in_str = ch
                elif ch == "{":
                    depth += 1
                elif ch == "}":
                    depth -= 1
                    if depth == 0:
                        break
            j += 1
        inner = css[brace + 1 : j]
        if scheme == "dark":
            out.append(inner)
        # Drop light-mode @media root override on Station Max dark TV UI
        i = j + 1
    return "".join(out)


def _downgrade_where_is_balanced(css: str) -> str:
    out = []
    i = 0
    n = len(css)
    low = css.lower()
    while i < n:
        p1 = low.find(":where(", i)
        p2 = low.find(":is(", i)
        candidates = [p for p in (p1, p2) if p >= 0]
        if not candidates:
            out.append(css[i:])
            break
        pos = min(candidates)
        plen = 7 if pos == p1 else 4
        out.append(css[i:pos])
        j = pos + plen
        depth = 1
        in_str = None
        while j < n and depth > 0:
            ch = css[j]
            if in_str:
                if ch == "\\" and j + 1 < n:
                    j += 2
                    continue
                if ch == in_str:
                    in_str = None
            else:
                if ch in ('"', "'"):
                    in_str = ch
                elif ch == "(":
                    depth += 1
                elif ch == ")":
                    depth -= 1
                    if depth == 0:
                        break
            j += 1
        if depth != 0:
            out.append(css[pos:])
            break
        inner = _downgrade_where_is_balanced(css[pos + plen : j]).strip()
        if "::" in inner:
            out.append(css[pos : pos + plen] + inner + ")")
        elif "," in inner:
            out.append(f":-webkit-any({inner})")
        else:
            out.append(inner)
        i = j + 1
    return "".join(out)


def downgrade_css(css: str) -> str:
    css = _extract_css_properties(css)
    css = unwrap_css_layers(css)
    css = _unwrap_prefers_color_scheme(css)
    css = _OKLCH_RE.sub(_oklch_to_srgb_css, css)
    css = _replace_color_mix(css)
    css = _downgrade_where_is_balanced(css)
    css = _DV_UNIT_RE.sub(r"\1v\2", css)
    css = _INSET_BOUNDARY_RE.sub(_expand_inset_prop, css)
    css = _INSET_RE.sub(_expand_inset_prop, css)
    css = _INSET_BS_RE.sub(r"\1top:", css)
    css = _INSET_BE_RE.sub(r"\1bottom:", css)
    css = _INSET_IS_RE.sub(r"\1left:", css)
    css = _INSET_IE_RE.sub(r"\1right:", css)
    return css


def inject_html_bridge(html: str) -> str:
    inj = '<script src="/_ym_bridge.js"></script><link rel="stylesheet" href="/_ym_tv.css" />'
    if "/_ym_bridge.js" in html:
        return html
    m = re.search(r"<head(?:\s[^>]*)?>", html, re.I)
    if m:
        pos = m.end()
        return html[:pos] + inj + html[pos:]
    return inj + html


def find_esbuild_js():
    for cand in ESBUILD_JS_CANDIDATES:
        if os.path.isfile(cand):
            return cand
    return None


def transpile_js_tree_with_esbuild(root_dir: str, esbuild_js: str):
    if not os.path.isfile(NODE_EXE):
        raise RuntimeError(f"Node.js not found: {NODE_EXE}")

    node_script = """
const fs = require("fs");
const path = require("path");
const esbuild = require(process.argv[2]);
const rootDir = process.argv[3];

function walk(dir) {
  let out = [];
  for (const name of fs.readdirSync(dir)) {
    const full = path.join(dir, name);
    const st = fs.statSync(full);
    if (st.isDirectory()) out = out.concat(walk(full));
    else if (name.endsWith(".js")) out.push(full);
  }
  return out;
}

const files = walk(rootDir);
let count = 0;
for (const f of files) {
  const src = fs.readFileSync(f, "utf8");
  const res = esbuild.transformSync(src, {
    loader: "js",
    target: "chrome66",
    legalComments: "none",
    charset: "utf8"
  });
  fs.writeFileSync(f, res.code, "utf8");
  count++;
}
console.log("Transpiled JS files:", count);
"""
    with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False, encoding="utf-8") as nf:
        nf.write(node_script)
        script_path = nf.name
    try:
        subprocess.run(
            [NODE_EXE, script_path, esbuild_js, root_dir],
            check=True,
        )
    finally:
        try:
            os.remove(script_path)
        except OSError:
            pass


def main():
    asar_path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_ASAR
    if not os.path.isfile(asar_path):
        print(f"ERROR: app.asar not found: {asar_path}", file=sys.stderr)
        sys.exit(1)

    esbuild_js = find_esbuild_js()
    if not esbuild_js:
        print("ERROR: esbuild npm package not found. Install esbuild first.", file=sys.stderr)
        sys.exit(1)

    os.makedirs(os.path.dirname(OUT_TAR_GZ), exist_ok=True)
    stage_dir = tempfile.mkdtemp(prefix="ym_stage_")
    try:
        print(f"Reading {asar_path}...")
        with open(asar_path, "rb") as fp:
            hdr, base_off = read_asar_index(fp)
            entries = walk_asar(hdr)
            extracted = 0
            css_cnt = 0
            html_cnt = 0
            for path_rel, size, offset, unpacked in entries:
                if not path_rel.startswith("app/"):
                    continue
                rel = path_rel[4:]  # strip "app/"
                if not rel or rel.endswith(".map"):
                    continue
                if unpacked:
                    continue
                fp.seek(base_off + offset)
                raw = fp.read(size)

                if rel.endswith(".css"):
                    raw = downgrade_css(raw.decode("utf-8", "replace")).encode("utf-8")
                    css_cnt += 1
                elif rel.endswith(".html"):
                    raw = inject_html_bridge(raw.decode("utf-8", "replace")).encode("utf-8")
                    html_cnt += 1

                dst = os.path.join(stage_dir, rel.replace("/", os.sep))
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                with open(dst, "wb") as out_f:
                    out_f.write(raw)
                extracted += 1

        # Add our TV bridge script + CSS
        with open(os.path.join(stage_dir, "_ym_bridge.js"), "w", encoding="utf-8") as bf:
            bf.write(YM_BRIDGE_JS)
        with open(os.path.join(stage_dir, "_ym_tv.css"), "w", encoding="utf-8") as cf:
            cf.write(YM_TV_CSS)

        print(f"Extracted {extracted} files (css={css_cnt}, html={html_cnt}). Transpiling JS -> chrome66...")
        transpile_js_tree_with_esbuild(stage_dir, esbuild_js)

        tmp_tgz = OUT_TAR_GZ + ".tmp"
        print(f"Packing {OUT_TAR_GZ}...")
        with tarfile.open(tmp_tgz, "w:gz", compresslevel=6) as tf:
            for root, dirs, files in os.walk(stage_dir):
                dirs.sort()
                for fname in sorted(files):
                    full = os.path.join(root, fname)
                    arcname = os.path.relpath(full, stage_dir).replace("\\", "/")
                    tf.add(full, arcname=arcname)
        shutil.move(tmp_tgz, OUT_TAR_GZ)
        sz_mb = os.path.getsize(OUT_TAR_GZ) / (1024.0 * 1024.0)
        print(f"SUCCESS: {OUT_TAR_GZ} ({sz_mb:.2f} MB)")
    finally:
        shutil.rmtree(stage_dir, ignore_errors=True)


if __name__ == "__main__":
    main()
