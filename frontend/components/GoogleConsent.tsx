"use client";

import { useEffect, useSyncExternalStore } from "react";
import { GOOGLE_CONSENT_KEY, readGoogleConsent, readServerGoogleConsent, subscribe } from "@/lib/consent";

const KEY = GOOGLE_CONSENT_KEY;
const TAG = "G-L8GY9BMQ4D";
type AnalyticsWindow = Window & { dataLayer?: unknown[]; gtag?: (...args: unknown[]) => void };

export default function GoogleConsent() {
  const consent = useSyncExternalStore(subscribe, readGoogleConsent, readServerGoogleConsent);
  useEffect(() => {
    if (consent === "yes") start();
    else if (consent === "no" && document.getElementById("vedal-google-analytics")) window.location.reload();
  }, [consent]);
  return null;
}

function start() {
  if (document.getElementById("vedal-google-analytics")) return;
  try { if (localStorage.getItem(KEY) !== "yes") return; } catch { return; }
  const w = window as AnalyticsWindow;
  w.dataLayer = w.dataLayer || [];
  // Google tag bootstrap queues the native arguments object.
  // eslint-disable-next-line prefer-rest-params
  w.gtag = w.gtag || function () { w.dataLayer?.push(arguments); };
  const allowed = new Set(["/", "/about", "/production", "/products", "/service", "/news", "/contacts"]);
  const normalized = location.pathname.replace(/\/$/, "") || "/";
  const url = location.origin + (allowed.has(normalized) ? normalized : "/other");
  let referrer = "";
  try { if (document.referrer) referrer = new URL(document.referrer).origin; } catch { /* omit */ }
  w.gtag("js", new Date());
  w.gtag("config", TAG, {send_page_view:false, allow_google_signals:false, allow_ad_personalization_signals:false, page_location:url, page_referrer:referrer});
  w.gtag("event", "page_view", {page_location:url, page_title:"VEDAL", page_referrer:referrer});
  const script = document.createElement("script"); script.id = "vedal-google-analytics"; script.async = true;
  script.src = "https://www.googletagmanager.com/gtag/js?id=" + TAG;
  document.head.appendChild(script);
}
