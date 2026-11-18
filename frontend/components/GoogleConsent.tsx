"use client";

import { useEffect, useState } from "react";

const KEY = "vedal.google.analytics.v1";
const TAG = "G-L8GY9BMQ4D";
type AnalyticsWindow = Window & { dataLayer?: unknown[]; gtag?: (...args: unknown[]) => void };

export default function GoogleConsent() {
  const [open, setOpen] = useState(false);
  useEffect(() => {
    let decision: string | null = null;
    try { decision = localStorage.getItem(KEY); } catch { return; }
    if (decision === "yes") { start(); return; }
    if (decision === "no") return;
    let active = true;
    const maybeShow = () => {
      if (active && !document.querySelector('[aria-label="Использование cookie"]')) {
        setOpen(true); observer.disconnect();
      }
    };
    const observer = new MutationObserver(maybeShow);
    observer.observe(document.body, { childList: true, subtree: true });
    const timer = window.setTimeout(maybeShow, 250);
    return () => { active = false; observer.disconnect(); window.clearTimeout(timer); };
  }, []);
  function choose(allow: boolean) {
    try { localStorage.setItem(KEY, allow ? "yes" : "no"); } catch { setOpen(false); return; }
    setOpen(false);
    if (allow) start(); else window.location.reload();
  }
  return <>
    <button type="button" onClick={() => setOpen(true)} style={{display:"block",margin:"12px auto",padding:"8px",border:"1px solid #888",background:"white",color:"#222"}}>Google Analytics</button>
    {open && <aside role="dialog" aria-label="Google Analytics" style={{position:"fixed",zIndex:99999,bottom:16,left:16,right:16,maxWidth:460,padding:20,background:"white",color:"#111",border:"1px solid #888",boxShadow:"0 8px 24px #0004"}}>
      <strong>Разрешить аналитику Google?</strong>
      <p>Google Analytics получит технические данные о посещении и cookie; данные могут передаваться за пределы России. Содержимое форм не передаётся. Сайт работает без аналитики; выбор можно изменить кнопкой «Google Analytics».</p>
      <button type="button" onClick={() => choose(false)}>Не разрешать</button>{" "}
      <button type="button" onClick={() => choose(true)}>Разрешить Google</button>
    </aside>}
  </>;
}

function start() {
  if (document.getElementById("vedal-google-analytics")) return;
  try { if (localStorage.getItem(KEY) !== "yes") return; } catch { return; }
  const w = window as AnalyticsWindow;
  w.dataLayer = w.dataLayer || [];
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
