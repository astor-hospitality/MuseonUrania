"use client";

import styles from "./Footer.module.css";

export default function CookieSettingsLink() {
  return <button type="button" className={styles.cookieSettings} onClick={() => window.dispatchEvent(new Event("vedal:cookie-settings"))}>Настройки cookie</button>;
}
