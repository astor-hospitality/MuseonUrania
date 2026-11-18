"use client";

import { useEffect, useRef, useSyncExternalStore } from "react";
import { usePathname } from "next/navigation";
import { installMetrika, metrikaId, reachGoal, trackPageView } from "@/lib/analytics";
import { readConsent, readServerConsent, subscribe } from "@/lib/consent";

/**
 * Яндекс Метрика: подключение счётчика и отправка целей. Issue #53.
 *
 * Два условия, и оба обязательны: номер счётчика задан
 * (`NEXT_PUBLIC_YANDEX_METRIKA_ID`) и посетитель согласился в плашке.
 * Не выполнено любое — со страницы не уходит ни одного запроса в Яндекс,
 * и скрипт счётчика даже не загружается. Это и есть смысл слова «до»
 * в формулировке задачи: баннер согласия ДО загрузки счётчика, а не
 * рядом с уже работающим.
 *
 * ————— почему цели ловятся делегированием —————
 *
 * Имена целей уже расставлены по разметке атрибутом `data-analytics` —
 * список утверждён в docs/frontend/implementation_checklist.md. Один
 * обработчик на документе превращает их в цели Метрики, и ни кнопка,
 * ни карточка, ни ссылка на документ не знают, что аналитика существует.
 *
 * Альтернатива — обработчик в каждом из десятка мест — стоила бы десяти
 * импортов и одной забытой кнопки: цель, которую забыли отправить, ничем
 * не отличается от цели, которой не было. Атрибут в разметке видно глазами.
 */
export default function Analytics() {
  const consent = useSyncExternalStore(subscribe, readConsent, readServerConsent);
  const allowed = metrikaId !== null && consent === "analytics";
  const previouslyAllowed = useRef(allowed);
  useEffect(() => {
    if (previouslyAllowed.current && !allowed && consent === "necessary") window.location.reload();
    previouslyAllowed.current = allowed;
  }, [allowed, consent]);

  // Только путь. `useSearchParams` брать нельзя: он переводит всю страницу
  // на отрисовку в браузере, а каталог, новости и документы у нас уезжают
  // в статику на сборке — это свойство №1 из спеки. Строка запроса всё
  // равно попадает в адрес визита: её читаем из window, где она настоящая.
  const pathname = usePathname();

  useEffect(() => {
    if (!allowed || !metrikaId) return;
    installMetrika(metrikaId);
  }, [allowed]);

  // Первый просмотр считает сам `init`, поэтому здесь только переходы.
  // Разделять их нечем — эффект зависит от адреса и срабатывает в том
  // числе на первом, — поэтому первый пропускается флагом.
  useEffect(() => {
    if (!allowed) return;
    if (first) {
      first = false;
      return;
    }
    trackPageView(pathname + window.location.search);
  }, [allowed, pathname]);

  useEffect(() => {
    if (!allowed) return;
    const onClick = (event: MouseEvent) => {
      const target = event.target;
      if (!(target instanceof Element)) return;
      const marked = target.closest<HTMLElement>("[data-analytics]");
      const goal = marked?.dataset.analytics;
      if (goal) reachGoal(goal);
    };
    // Слушаем на фазе всплытия у документа: клик по кнопке, которая
    // перерисовывается тут же (плашка, карточка), успевает всплыть,
    // а обработчик переживает перерисовку — он не на самой кнопке.
    document.addEventListener("click", onClick);
    return () => document.removeEventListener("click", onClick);
  }, [allowed]);

  return null;
}

// Модульная переменная, а не состояние: между монтированиями компонента
// (а Next размонтирует его при смене раскладки) счётчик всё равно один,
// и «первый просмотр» тоже один на загрузку страницы.
let first = true;
