import Header from "@/components/Header";
import ServiceNotice from "@/components/ServiceNotice";
import Footer from "@/components/Footer";
import VedalinaWidget from "@/components/VedalinaWidget";
import CookieNotice from "@/components/CookieNotice";
import Analytics from "@/components/Analytics";
import ImageGuard from "@/components/ImageGuard";
import LogoPreloader from "@/components/LogoPreloader";
import Motion from "@/components/Motion";
import { fontVariables } from "@/app/fonts";
import {
  JsonLd,
  organizationStructuredData,
  websiteStructuredData,
} from "@/lib/structured-data";

// Оболочка публичного сайта: `<html>`, `<body>` и всё, что стоит на каждой
// странице.
//
// Отдельным компонентом, а не прямо в `app/(site)/layout.tsx`: корневых
// layout'ов в приложении два — сайт и админка (`app/(admin)`), — и они
// намеренно не наследуют друг у друга. Оформление сайта в админку
// не приезжает, а `<html>` рисует ровно один из них.
//
// Сайт одноязычный: русский и только он. Английская и китайская версии
// сняты 9 сентября по решению владельца портала, адреса `/en/…` и `/zh/…`
// уводятся редиректом на корень (`next.config.ts`). Поэтому `lang` у
// `<html>` — константа, а не проп: меняться ему больше не с чем.

export default function SiteShell({ children }: { children: React.ReactNode }) {
  return (
    // Та же причина, что и в админке: расширения браузера дописывают свои
    // атрибуты в <html> и <body> раньше, чем отрисуется React. Подавление
    // действует только на атрибуты этих узлов и не распространяется на детей —
    // расхождение внутри страницы отловится как обычно.
    <html lang="ru" className={fontVariables} suppressHydrationWarning>
      <body suppressHydrationWarning>
        <JsonLd id="vedal-organization-jsonld" data={organizationStructuredData()} />
        <JsonLd id="vedal-website-jsonld" data={websiteStructuredData()} />
        <LogoPreloader />
        <Motion />
        <div className="frame">
          <Header />
          <ServiceNotice />
          {children}
        </div>
        <Footer />
        {/* Плавающий чат — на всех страницах. Сайт русский, а Ведалина
            отвечает на языке вопроса (backend/assistant/Guardrails) —
            это отдельное решение и на разметку страницы не влияет. */}
        <VedalinaWidget />
        <CookieNotice />
        {/* Счётчик Метрики: молчит, пока не задан номер и не дано согласие. */}
        <Analytics />
        <ImageGuard />
      </body>
    </html>
  );
}
