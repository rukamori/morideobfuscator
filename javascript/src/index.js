import Innertube, { ClientType, Platform } from "youtubei.js/web.bundle";
import {
  CustomEvent,
  File,
  FormData,
  Headers,
  ReadableStream,
  Request,
  Response,
  base64ToBytes,
  bytesToBase64,
  fetch,
  installWebPlatform,
} from "./web-platform.js";

const RUNTIME_VERSION = "18.0.0";
const SUPPORTED_AUDIO_MIME_TYPES = new Set([
  "audio/mp4",
  "audio/webm",
  "audio/ogg",
  "audio/mpeg",
  "audio/3gpp",
]);
const ANONYMOUS_CLIENTS = ["VISIONOS", "ANDROID_VR", "IOS", "YTMUSIC"];
const AUTHENTICATED_CLIENTS = ["WEB_CREATOR", "YTMUSIC", "WEB_EMBEDDED"];

installWebPlatform();

class AndroidCache {
  get cache_dir() {
    return "android";
  }

  async get(key) {
    const encoded = await globalThis.__archiveTuneCacheRead(String(key));
    return encoded ? base64ToBytes(encoded).buffer : undefined;
  }

  async set(key, value) {
    const bytes = value instanceof ArrayBuffer ? new Uint8Array(value) : new Uint8Array(value.buffer);
    await globalThis.__archiveTuneCacheWrite(
      JSON.stringify({ key: String(key), value: bytesToBase64(bytes) }),
    );
  }

  async remove(key) {
    await globalThis.__archiveTuneCacheRemove(String(key));
  }
}

Platform.load({
  runtime: "unknown",
  server: true,
  Cache: AndroidCache,
  sha1Hash: globalThis.__archiveTuneSha1,
  uuidv4: globalThis.__archiveTuneUuid,
  eval(data, environment) {
    const names = Object.keys(environment || {});
    const values = names.map((name) => environment[name]);
    return new Function(...names, `"use strict";\n${data.output}`)(...values);
  },
  fetch,
  Request,
  Response,
  Headers,
  FormData,
  File,
  ReadableStream,
  CustomEvent,
});

let session;
let sessionIdentity;

function normalizedString(value) {
  const normalized = typeof value === "string" ? value.trim() : "";
  return normalized || undefined;
}

function createSessionIdentity(request) {
  return JSON.stringify({
    authFingerprint: request.authFingerprint,
    cookie: request.cookie,
    visitorData: request.visitorData,
    sessionPoToken: request.sessionPoToken,
    language: request.language,
    location: request.location,
    timezone: request.timezone,
  });
}

async function getSession(request) {
  const identity = createSessionIdentity(request);
  if (session && identity === sessionIdentity) return session;
  const authenticated = Boolean(normalizedString(request.cookie));
  session = await Innertube.create({
    cache: new AndroidCache(),
    client_type: authenticated ? ClientType.WEB_CREATOR : ClientType.VISIONOS,
    cookie: normalizedString(request.cookie),
    visitor_data: normalizedString(request.visitorData),
    po_token: normalizedString(request.sessionPoToken),
    lang: normalizedString(request.language) || "en",
    location: normalizedString(request.location) || "US",
    timezone: normalizedString(request.timezone) || "UTC",
    generate_session_locally: true,
    retrieve_innertube_config: false,
    retrieve_player: true,
    enable_session_cache: false,
    fetch,
  });
  sessionIdentity = identity;
  return session;
}

function bitrateOf(format) {
  return Number(format.average_bitrate || format.bitrate || 0);
}

function sampleRateOf(format) {
  return Number(format.audio_sample_rate || 0);
}

function isEligibleAudioFormat(format) {
  const mimeType = String(format.mime_type || "").split(";", 1)[0].toLowerCase();
  return Boolean(format.has_audio) &&
    !format.has_video &&
    !format.is_type_otf &&
    !format.fair_play_key_uri &&
    !format.drm_track_type &&
    !(format.drm_families && format.drm_families.length) &&
    SUPPORTED_AUDIO_MIME_TYPES.has(mimeType) &&
    Boolean(format.url || format.signature_cipher || format.cipher);
}

function compareFormats(first, second) {
  return bitrateOf(first) - bitrateOf(second) ||
    sampleRateOf(first) - sampleRateOf(second) ||
    String(first.itag).localeCompare(String(second.itag));
}

function preferredFormat(formats, request) {
  if (request.pinnedItag) {
    const pinned = formats.find((format) => Number(format.itag) === Number(request.pinnedItag));
    if (pinned) return pinned;
  }
  const effectiveQuality = request.quality === "AUTO"
    ? request.networkMetered ? "HIGH" : "HIGHEST"
    : request.quality;
  const target = effectiveQuality === "LOW" ? 70000 : effectiveQuality === "HIGH" ? 160000 : null;
  if (target === null) return formats[formats.length - 1];
  const notAboveTarget = formats.filter((format) => bitrateOf(format) <= target);
  return notAboveTarget.length ? notAboveTarget[notAboveTarget.length - 1] : formats[0];
}

function orderedFormats(formats, request) {
  const sorted = [...formats].sort(compareFormats);
  const preferred = preferredFormat(sorted, request);
  const descending = [...sorted].reverse();
  return [preferred, ...descending.filter((format) => format !== preferred)];
}

function extractExpiry(url) {
  const value = Number(new URL(url).searchParams.get("expire"));
  return Number.isFinite(value) && value > 0 ? value * 1000 : Date.now() + 300000;
}

function thumbnailUrl(basicInfo) {
  const thumbnails = basicInfo?.thumbnail;
  if (!Array.isArray(thumbnails) || thumbnails.length === 0) return undefined;
  return normalizedString(thumbnails[thumbnails.length - 1]?.url);
}

function playbackTrackingUrl(info) {
  return normalizedString(info.page?.[0]?.playback_tracking?.videostats_playback_url);
}

function failureKind(error) {
  const message = String(error?.message || error || "youtubei.js resolution failed");
  const normalized = message.toLowerCase();
  if (
    normalized.includes("sign in") ||
    normalized.includes("login required") ||
    normalized.includes("confirm your age") ||
    normalized.includes("age-restricted")
  ) return "LOGIN_REQUIRED";
  if (normalized.includes("unavailable") || normalized.includes("private video")) return "UNAVAILABLE";
  if (normalized.includes("format")) return "NO_FORMAT";
  if (normalized.includes("decipher") || normalized.includes("signature") || normalized.includes("nsig")) return "DECIPHER";
  if (normalized.includes("timed out") || normalized.includes("timeout")) return "TIMEOUT";
  if (normalized.includes("http ")) return "HTTP";
  if (error instanceof TypeError && normalized.includes("network")) return "NETWORK";
  return "INTERNAL";
}

async function resolveWithClient(youtube, request, client) {
  const info = await youtube.getBasicInfo(request.mediaId, {
    client,
    po_token: normalizedString(request.videoPoToken),
  });
  const status = normalizedString(info.playability_status?.status);
  const reason = normalizedString(info.playability_status?.reason);
  const formats = [
    ...(info.streaming_data?.adaptive_formats || []),
    ...(info.streaming_data?.formats || []),
  ].filter(isEligibleAudioFormat);
  if (formats.length === 0) {
    const message = reason || (status ? `YouTube playability status: ${status}` : "No direct audio format");
    const error = new Error(message);
    error.kind = status === "LOGIN_REQUIRED" ? "LOGIN_REQUIRED" : status && status !== "OK" ? "UNAVAILABLE" : "NO_FORMAT";
    throw error;
  }

  let decipherFailure;
  for (const format of orderedFormats(formats, request)) {
    try {
      const url = await format.decipher(youtube.session.player);
      const parsedUrl = new URL(url);
      if (parsedUrl.protocol !== "https:" && parsedUrl.protocol !== "http:") continue;
      const mime = String(format.mime_type || "audio/webm");
      const mimeType = mime.split(";", 1)[0];
      const codecs = /codecs="([^"]+)"/.exec(mime)?.[1] || "";
      const basicInfo = info.basic_info || {};
      const headers = {
        Accept: "*/*",
        Origin: "https://www.youtube.com",
        Referer: "https://www.youtube.com/",
      };
      const userAgent = normalizedString(youtube.session.context?.client?.userAgent);
      if (userAgent) headers["User-Agent"] = userAgent;
      return {
        url: parsedUrl.toString(),
        headers,
        formatId: Number(format.itag),
        mimeType,
        codecs,
        bitrate: bitrateOf(format),
        sampleRate: sampleRateOf(format) || null,
        contentLength: Number(format.content_length || 0),
        expiresAtMs: extractExpiry(parsedUrl),
        runtimeVersion: RUNTIME_VERSION,
        title: normalizedString(basicInfo.title),
        durationSeconds: Number(basicInfo.duration || 0) || null,
        thumbnailUrl: thumbnailUrl(basicInfo),
        loudnessDb: Number.isFinite(format.loudness_db) ? format.loudness_db : null,
        perceptualLoudnessDb: null,
        playbackTrackingUrl: playbackTrackingUrl(info),
      };
    } catch (error) {
      decipherFailure = error;
    }
  }
  const error = new Error(decipherFailure?.message || "youtubei.js could not decipher an audio URL");
  error.kind = "DECIPHER";
  throw error;
}

async function resolve(requestJson) {
  try {
    const request = JSON.parse(requestJson);
    if (!/^[A-Za-z0-9_-]{6,32}$/.test(String(request.mediaId || ""))) {
      return JSON.stringify({
        ok: false,
        error: { kind: "INVALID_RESPONSE", message: "Invalid YouTube media ID" },
      });
    }
    const youtube = await getSession(request);
    const authenticated = Boolean(normalizedString(request.cookie));
    const clients = authenticated ? AUTHENTICATED_CLIENTS : ANONYMOUS_CLIENTS;
    let firstFallback;
    let mostRelevantFailure;
    for (const client of clients) {
      try {
        const value = await resolveWithClient(youtube, request, client);
        if (!request.pinnedItag || value.formatId === Number(request.pinnedItag)) {
          return JSON.stringify({ ok: true, value });
        }
        firstFallback ||= value;
      } catch (error) {
        const kind = error.kind || failureKind(error);
        if (!mostRelevantFailure || kind === "LOGIN_REQUIRED" || kind === "DECIPHER") {
          mostRelevantFailure = { kind, message: String(error.message || error) };
        }
      }
    }
    if (firstFallback) return JSON.stringify({ ok: true, value: firstFallback });
    return JSON.stringify({
      ok: false,
      error: mostRelevantFailure || { kind: "NO_FORMAT", message: "No supported audio format" },
    });
  } catch (error) {
    return JSON.stringify({
      ok: false,
      error: {
        kind: error.kind || failureKind(error),
        message: String(error?.message || error || "youtubei.js resolution failed"),
      },
    });
  }
}

globalThis.ArchiveTuneYoutubei = Object.freeze({
  version: RUNTIME_VERSION,
  resolve,
  reset() {
    session = undefined;
    sessionIdentity = undefined;
  },
});
