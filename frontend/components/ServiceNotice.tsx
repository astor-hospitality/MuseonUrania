import { serviceNotice } from "@/content/service-notice";
import { site } from "@/content/site";
import styles from "./ServiceNotice.module.css";

export default function ServiceNotice() {
  if (!serviceNotice.active) return null;

  return (
    <aside className={styles.notice} aria-label="Статус работы Ведала">
      <div className={styles.copy}>
        <strong>{serviceNotice.title}</strong>
        <p>{serviceNotice.message}</p>
      </div>
      <div className={styles.links}>
        <a href={`tel:${site.phone.replace(/\s/g, "")}`}>{site.phone}</a>
        <a href={`mailto:${site.email}`}>{site.email}</a>
        <a href={serviceNotice.statusUrl} target="_blank" rel="noopener noreferrer">
          Статус восстановления ↗
        </a>
      </div>
    </aside>
  );
}
