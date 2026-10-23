/**
 * Заголовки безопасности — из приложения, а не из прокси.
 *
 * ————— почему они здесь —————
 *
 * Политика содержимого написана и настроена в `backend/proxy/Caddyfile`.
 * На стенде это не даёт ничего: домен там держит общий nginx рядом с чужими
 * сайтами, Caddy в цепочке не участвует, и заголовка у страниц нет вовсе —
 * проверено на живом домене 8 сентября.
 *
 * Хуже самого отсутствия то, что этого никто не замечал. Правки политики —
 * кадр Яндекс.Карт, источники Метрики — проверялись `caddy validate`,
 * то есть в конфигурации, которая на стенде не применяется. Проверка была
 * зелёной, защита не работала.
 *
 * Заголовок, выданный приложением, не зависит от того, что стоит впереди:
 * Caddy, nginx или ничего. В Caddyfile политика остаётся — одинаковые
 * заголовки безвредны, а разные складываются в пересечение, то есть
 * в более строгое из двух.
 *
 * ————— почему функция, а не константа —————
 *
 * Чтобы её можно было проверить. Константа, собранная из `process.env`
 * на уровне модуля, в тесте требует подмены окружения и сброса кэша
 * модулей; функция принимает окружение аргументом и остаётся чистой.
 * Политика — ровно тот код, который обязан быть проверяемым: сломать её
 * можно молча в обе стороны, и обе одинаково плохи.
 */

type Env = Record<string, string | undefined>;

/**
 * Источник адреса — без пути внутри него.
 *
 * Кривой адрес не роняет сборку: политика просто станет строже на один
 * источник. Ронять сайт из-за опечатки в необязательной переменной —
 * цена выше пользы.
 */
function origin(raw: string | undefined): string | null {
  const value = (raw ?? "").trim();
  if (!value) return null;
  try {
    return new URL(value).origin;
  } catch {
    return null;
  }
}

/** Адреса счётчика. Пусто, пока счётчик не включён. */
function metrika(env: Env): string[] {
  return env.NEXT_PUBLIC_YANDEX_METRIKA_ID?.trim()
    ? ["https://mc.yandex.ru", "https://mc.yandex.com", "https://mc.webvisor.org", "https://mc.webvisor.com", "https://yastatic.net"]
    : [];
}

/**
 * Кадр карты разрешён всегда, а не по признаку согласия.
 *
 * Политика — это то, что странице ПОЗВОЛЕНО; грузить кадр или нет, решает
 * согласие посетителя в самой странице. Разрешение без загрузки не
 * отправляет в Яндекс ничего.
 */
const MAP = ["https://yandex.ru", "https://yandex.com"];

export function contentSecurityPolicy(env: Env): string {
  const media = origin(env.NEXT_PUBLIC_MEDIA_URL);
  const api = origin(env.NEXT_PUBLIC_API_URL);
  const keycloak = origin(env.NEXT_PUBLIC_OIDC_ISSUER);
  const counter = metrika(env);

  const join = (...parts: (string | null | undefined)[]) => parts.filter(Boolean).join(" ");

  return [
    "default-src 'self'",
    // 'unsafe-inline' нужен самому Next: разметку он оживляет встроенным
    // скриптом с данными страницы. Убрать его можно только одноразовым
    // ключом на каждый ответ, а ключ требует серверного шага перед каждой
    // страницей — то есть отменяет свойство «падение бэкенда не роняет сайт».
    join("script-src 'self' 'unsafe-inline'", ...counter),
    "object-src 'none'",
    // blob: — портреты сотрудников в админке, и без него их не видно нигде.
    //
    // Дверь портрета закрыта токеном, а браузер, идя по адресу в src, токена
    // не прикладывает. Поэтому байты приезжают обычным запросом с заголовком
    // Authorization и превращаются в blob: (frontend/app/(admin)/admin/
    // portraits.tsx). Под 'self' такой адрес НЕ подпадает — это отдельная
    // схема, и её надо назвать.
    //
    // Стоило это ровно того, что описано выше про молчаливую поломку:
    // владелец загрузил своё фото, портал его принял, сохранил и записал
    // в журнал «поставил себе портрет» — а в кружке остался серый круг.
    // Ошибки на экране нет, запрос за портретом успешен, ломается последний
    // шаг, показ, и ломается он в заголовке ответа страницы.
    //
    // Послабления здесь нет: blob: — адрес, заведённый этой же вкладкой
    // из байтов, которые она уже получила. Чужого через него не приедет.
    join("img-src 'self' data: blob:", media, ...counter),
    "style-src 'self' 'unsafe-inline'",
    // Хранилище медиа — ради видео в новостях: ролики лежат в бакете рядом
    // с фотографиями и отдаются оттуда же. Без источника в media-src браузер
    // молча не грузит <video> — плеер стоит, а нажатие ничего не делает.
    join("media-src 'self' blob:", media),
    "font-src 'self' data:",
    join("connect-src 'self'", api, keycloak, ...counter,
      ...(counter.length ? ["wss://mc.yandex.ru", "wss://mc.yandex.com", "wss://mc.webvisor.org", "wss://mc.webvisor.com"] : [])),
    join("frame-src 'self'", ...MAP, ...counter, counter.length ? "blob:" : null),
    ...(counter.length ? [join("child-src 'self' blob:", ...counter)] : []),
    "frame-ancestors 'none'",
    "base-uri 'self'",
    // Вход уводит браузер на Keycloak формой — без этого он молча не уйдёт.
    join("form-action 'self'", keycloak),
  ].join("; ");
}

export function securityHeaders(env: Env): { key: string; value: string }[] {
  return [
    { key: "Content-Security-Policy", value: contentSecurityPolicy(env) },
    { key: "X-Content-Type-Options", value: "nosniff" },
    { key: "X-Frame-Options", value: "DENY" },
    { key: "Referrer-Policy", value: "no-referrer" },
  ];
}
