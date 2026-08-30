import Innertube, { ClientType, Platform, Player } from "youtubei.js/web.bundle";
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
const FAILURE_KINDS = new Set([
  "LOGIN_REQUIRED",
  "UNAVAILABLE",
  "NO_FORMAT",
  "DECIPHER",
  "NETWORK",
  "HTTP",
  "TIMEOUT",
  "INVALID_RESPONSE",
  "INTERNAL",
  "PLAYER_REQUIRED",
]);

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
let playerInitialization;

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
    retrieve_player: false,
    enable_session_cache: false,
    fetch,
  });
  sessionIdentity = identity;
  playerInitialization = undefined;
  return session;
}

async function ensurePlayer(youtube, request) {
  if (youtube.session.player) return youtube.session.player;
  if (!playerInitialization) {
    playerInitialization = Player.create(
      youtube.session.cache,
      fetch,
      normalizedString(request.sessionPoToken),
    ).then((player) => {
      youtube.session.player = player;
      return player;
    });
  }
  const initialization = playerInitialization;
  try {
    return await initialization;
  } catch (error) {
    const failure = normalizedFailure(error, "youtubei.js player initialization failed");
    const playerError = new Error(failure.message);
    playerError.kind = failure.kind;
    playerError.playerInitialization = true;
    throw playerError;
  } finally {
    if (playerInitialization === initialization) playerInitialization = undefined;
  }
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

function formatRequiresPlayer(format) {
  if (format.signature_cipher || format.cipher || !normalizedString(format.url)) return true;
  try {
    return new URL(format.url).searchParams.has("n");
  } catch {
    return true;
  }
}

function playerRequiredError() {
  const error = new Error("A YouTube player is required to decipher this audio format");
  error.kind = "PLAYER_REQUIRED";
  return error;
}

function compareFormats(first, second) {
  return bitrateOf(first) - bitrateOf(second) ||
    sampleRateOf(first) - sampleRateOf(second) ||
    String(first.itag).localeCompare(String(second.itag));
}

function preferredFormat(formats, request) {
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
  const pinned = request.pinnedItag
    ? sorted.find((format) => Number(format.itag) === Number(request.pinnedItag))
    : undefined;
  const original = sorted.filter((format) => format.is_original);
  const primary = original.length ? original : sorted;
  const alternate = original.length ? sorted.filter((format) => !format.is_original) : [];
  const preferred = preferredFormat(primary, request);
  return [pinned, preferred, ...[...primary].reverse(), ...[...alternate].reverse()]
    .filter((format, index, ordered) => format && ordered.indexOf(format) === index);
}

function extractExpiry(url, streamingDataExpiry) {
  const parsedExpiry = streamingDataExpiry instanceof Date
    ? streamingDataExpiry.getTime()
    : Number(streamingDataExpiry);
  if (Number.isFinite(parsedExpiry) && parsedExpiry > Date.now()) return parsedExpiry;
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

function failureMessage(error, fallback = "youtubei.js resolution failed") {
  if (typeof error === "string") return normalizedString(error) || fallback;
  if (!error || typeof error !== "object") return fallback;
  return normalizedString(error.message) ||
    normalizedString(error.info?.reason) ||
    normalizedString(error.info?.message) ||
    normalizedString(error.info?.error_type) ||
    normalizedString(error.info?.status) ||
    fallback;
}

function structuredFailureKind(error) {
  if (!error || typeof error !== "object") return undefined;
  const candidates = [error.kind, error.info?.error_type, error.info?.status, error.status];
  for (const candidate of candidates) {
    const value = normalizedString(candidate)?.toUpperCase().replace(/[ -]+/g, "_");
    if (!value) continue;
    if (FAILURE_KINDS.has(value)) return value;
    if (
      value === "AGE_CHECK_REQUIRED" ||
      value === "AGE_RESTRICTED" ||
      value === "AGE_VERIFICATION_REQUIRED" ||
      value === "CONTENT_CHECK_REQUIRED" ||
      value === "AUTH_REQUIRED"
    ) return "LOGIN_REQUIRED";
    if (
      value === "UNPLAYABLE" ||
      value === "VIDEO_UNAVAILABLE" ||
      value === "CONTENT_UNAVAILABLE"
    ) return "UNAVAILABLE";
    if (value === "NO_STREAMING_DATA" || value === "FORMAT_NOT_FOUND") return "NO_FORMAT";
    if (value === "SIGNATURE_DECIPHER_FAILED" || value === "NSIG_DECIPHER_FAILED") return "DECIPHER";
    if (value === "FETCH_ERROR" || value === "FETCH_FAILED") return "NETWORK";
  }
  return undefined;
}

function failureKind(error) {
  const structured = structuredFailureKind(error);
  if (structured) return structured;
  const message = failureMessage(error);
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

function normalizedFailure(error, fallbackMessage) {
  return {
    kind: failureKind(error),
    message: failureMessage(error, fallbackMessage),
  };
}

function failurePriority(kind) {
  switch (kind) {
    case "LOGIN_REQUIRED": return 9;
    case "UNAVAILABLE": return 8;
    case "DECIPHER": return 7;
    case "NO_FORMAT": return 6;
    case "TIMEOUT": return 5;
    case "NETWORK": return 4;
    case "HTTP": return 3;
    case "INVALID_RESPONSE": return 2;
    case "PLAYER_REQUIRED": return 10;
    default: return 1;
  }
}

async function resolveWithClient(youtube, request, client, allowPlayer) {
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
    error.kind = status && status !== "OK"
      ? structuredFailureKind({ status }) || "UNAVAILABLE"
      : "NO_FORMAT";
    throw error;
  }

  const pinnedFormat = request.pinnedItag
    ? formats.find((format) => Number(format.itag) === Number(request.pinnedItag))
    : undefined;
  if (
    !allowPlayer &&
    !youtube.session.player &&
    pinnedFormat &&
    formatRequiresPlayer(pinnedFormat)
  ) {
    throw playerRequiredError();
  }

  let decipherFailure;
  let playerRequired = false;
  for (const format of orderedFormats(formats, request)) {
    const requiresPlayer = formatRequiresPlayer(format);
    if (requiresPlayer && !allowPlayer && !youtube.session.player) {
      playerRequired = true;
      continue;
    }
    const player = requiresPlayer
      ? youtube.session.player || await ensurePlayer(youtube, request)
      : undefined;
    try {
      const url = await format.decipher(player);
      const parsedUrl = new URL(url);
      if (parsedUrl.protocol !== "https:" && parsedUrl.protocol !== "http:") continue;
      const sessionPoToken = normalizedString(request.sessionPoToken);
      if (
        sessionPoToken &&
        parsedUrl.searchParams.get("sabr") !== "1" &&
        !parsedUrl.searchParams.has("pot")
      ) {
        parsedUrl.searchParams.set("pot", sessionPoToken);
      }
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
      const audioConfig = info.player_config?.audio_config;
      return {
        url: parsedUrl.toString(),
        headers,
        formatId: Number(format.itag),
        mimeType,
        codecs,
        bitrate: bitrateOf(format),
        sampleRate: sampleRateOf(format) || null,
        contentLength: Number(format.content_length || 0),
        expiresAtMs: extractExpiry(parsedUrl, info.streaming_data?.expires),
        runtimeVersion: RUNTIME_VERSION,
        title: normalizedString(basicInfo.title),
        durationSeconds: Number(basicInfo.duration || 0) || null,
        thumbnailUrl: thumbnailUrl(basicInfo),
        loudnessDb: Number.isFinite(format.loudness_db)
          ? format.loudness_db
          : Number.isFinite(audioConfig?.loudness_db) ? audioConfig.loudness_db : null,
        perceptualLoudnessDb: Number.isFinite(audioConfig?.perceptual_loudness_db)
          ? audioConfig.perceptual_loudness_db
          : null,
        playbackTrackingUrl: playbackTrackingUrl(info),
      };
    } catch (error) {
      decipherFailure = error;
    }
  }
  if (playerRequired && !allowPlayer) throw playerRequiredError();
  const error = new Error(decipherFailure?.message || "youtubei.js could not decipher an audio URL");
  error.kind = "DECIPHER";
  throw error;
}

async function resolve(requestJson, allowPlayer = false) {
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
        const value = await resolveWithClient(youtube, request, client, Boolean(allowPlayer));
        if (!request.pinnedItag || value.formatId === Number(request.pinnedItag)) {
          return JSON.stringify({ ok: true, value });
        }
        firstFallback ||= value;
      } catch (error) {
        const failure = normalizedFailure(error);
        if (
          !mostRelevantFailure ||
          failurePriority(failure.kind) > failurePriority(mostRelevantFailure.kind)
        ) {
          mostRelevantFailure = failure;
        }
        if (error?.playerInitialization) break;
      }
    }
    if (!allowPlayer && mostRelevantFailure?.kind === "PLAYER_REQUIRED") {
      return JSON.stringify({ ok: false, error: mostRelevantFailure });
    }
    if (firstFallback) return JSON.stringify({ ok: true, value: firstFallback });
    return JSON.stringify({
      ok: false,
      error: mostRelevantFailure || { kind: "NO_FORMAT", message: "No supported audio format" },
    });
  } catch (error) {
    return JSON.stringify({
      ok: false,
      error: normalizedFailure(error),
    });
  }
}

globalThis.ArchiveTuneYoutubei = Object.freeze({
  version: RUNTIME_VERSION,
  resolve,
  reset() {
    session = undefined;
    sessionIdentity = undefined;
    playerInitialization = undefined;
  },
});
