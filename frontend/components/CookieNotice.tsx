"use client";

import { useEffect, useState, useSyncExternalStore } from "react";
import Link from "next/link";
import { metrikaId } from "@/lib/analytics";
import { mapEmbedded } from "@/lib/maps";
import { answerCombined, readConsent, readServerConsent, readGoogleConsent, readServerGoogleConsent, subscribe } from "@/lib/consent";
import styles from "./CookieNotice.module.css";

export default function CookieNotice() {
  const yandex = useSyncExternalStore(subscribe, readConsent, readServerConsent);
  const google = useSyncExternalStore(subscribe, readGoogleConsent, readServerGoogleConsent);
  const [settings, setSettings] = useState(false);
  useEffect(() => {
    const open = () => setSettings(true);
    window.addEventListener("vedal:cookie-settings", open);
    return () => window.removeEventListener("vedal:cookie-settings", open);
  }, []);
  const unanswered = yandex === "unanswered" || google === "unanswered";
  if (!settings && !unanswered) return null;
  function choose(allow: boolean) {
    if (!answerCombined(allow)) return;
    setSettings(false);
    if (!allow) window.location.reload();
  }
  return (
    <aside className={styles.notice} role="complementary" aria-label="Использование cookie">
      <p className={styles.text}>
        С вашего согласия {metrikaId ? "Яндекс Метрика с Вебвизором и Google Analytics анализируют" : "Google Analytics анализирует"} посещения{mapEmbedded ? "; на странице контактов работает карта Яндекса" : ""}. Данные Google могут передаваться за пределы России. Без согласия сайт работает. {" "}
        <Link className={styles.link} href="/legal/privacy/">Подробнее</Link>
      </p>
      <div className={styles.buttons}>
        {settings && <button type="button" className={styles.decline} onClick={() => choose(false)}>Отозвать согласие</button>}
        <button type="button" className={styles.accept} onClick={() => choose(true)}>Принять</button>
        {settings && <button type="button" className={styles.decline} onClick={() => setSettings(false)}>Закрыть</button>}
      </div>
    </aside>
  );
}
