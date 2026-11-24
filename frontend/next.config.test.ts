import { readdirSync, existsSync } from "node:fs";
import { join } from "node:path";

import { describe, expect, it } from "vitest";

import config from "./next.config";

// Редиректы и заголовки кэша.
//
// Обе вещи ломаются молча и замечает их заказчик, а не мы: редирект видно
// только в выдаче поисковика через недели, заголовок кэша — по жалобе
// «у меня старый сайт», которую легко списать на браузер посетителя.
// Поэтому здесь проверяется не форма записи, а то, что от неё требуется.

/**
 * Настоящие маршруты публичного сайта — прочитанные с диска, а не
 * переписанные сюда руками.
 *
 * Список в тесте разошёлся бы с сайтом на первой же удалённой странице,
 * и разошёлся бы молча: тест остался бы зелёным, проверяя вчерашний сайт.
 * Ровно это и случилось с `/production/archive` — страницу убрали,
 * а карта сайта продолжала её обещать.
 *
 * Сегменты вида `[slug]` своего адреса не имеют, поэтому собираются отдельно:
 * в `dynamic` попадает раздел, у которого такой сегмент есть. Адрес карточки
 * изделия проверить списком нельзя — слаги живут в портале, — но проверить,
 * что раздел вообще умеет их показывать, можно и нужно.
 */
function realRoutes(): { routes: Set<string>; dynamic: Set<string> } {
  const root = join(import.meta.dirname, "app", "(site)");
  const routes = new Set<string>();
  const dynamic = new Set<string>();

  const walk = (dir: string, route: string) => {
    if (existsSync(join(dir, "page.tsx"))) routes.add(route);
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (!entry.isDirectory()) continue;
      if (entry.name.startsWith("[")) {
        if (existsSync(join(dir, entry.name, "page.tsx"))) dynamic.add(route);
        continue;
      }
      walk(join(dir, entry.name), `${route}${entry.name}/`);
    }
  };

  walk(root, "/");
  return { routes, dynamic };
}

const { routes, dynamic } = realRoutes();

/** Адрес, который сайт умеет показать: своей страницей или через `[slug]`. */
function isLivePage(path: string): boolean {
  if (routes.has(path)) return true;
  const parent = path.replace(/[^/]+\/$/, "");
  return parent !== path && dynamic.has(parent);
}

async function redirects() {
  return (await config.redirects?.()) ?? [];
}

/** Статические источники: те, где нет ни `:param`, ни условия по хосту. */
async function staticRedirects() {
  return (await redirects()).filter((rule) => !rule.source.includes(":") && !rule.has);
}

describe("редиректы", () => {
  it("сохраняет старую ссылку новости после исправления названия выставки", async () => {
    expect(await staticRedirects()).toContainEqual({
      source: "/news/aran-2026/",
      destination: "/news/zdravookhranenie-2026/",
      permanent: true,
    });
  });
  // Повод, с которого началась задача: в выдаче Google стоял `/contact`,
  // а маршрут на сайте — `/contacts/`. Переход из поиска давал 404.
  it("уводит /contact на /contacts/", async () => {
    expect(await staticRedirects()).toContainEqual({
      source: "/contact",
      destination: "/contacts/",
      permanent: true,
    });
  });

  // Страница фотоархива снята с публикации 16 сентября. Она была
  // опубликована, ссылка на неё могла уйти — 404 на её месте хуже,
  // чем «переехало сюда».
  it("уводит снятый с публикации /production/archive на /production/", async () => {
    expect(await staticRedirects()).toContainEqual({
      source: "/production/archive",
      destination: "/production/",
      permanent: true,
    });
  });

  // 301/308, а не 302: временный редирект поисковик не переносит в индекс
  // и продолжает держать в нём старый адрес.
  it("все правила постоянные", async () => {
    for (const rule of await redirects()) {
      expect(rule.permanent, rule.source).toBe(true);
    }
  });

  // Опечатка в адресе назначения не роняет ни сборку, ни страницу: она
  // просто уводит пришедшего из поиска с одного несуществующего адреса
  // на другой. Видно её только глазами и только на живом сайте.
  it("ведут на существующие страницы, а не на другой 404", async () => {
    for (const rule of await staticRedirects()) {
      expect(isLivePage(rule.destination), `${rule.source} → ${rule.destination}`).toBe(true);
    }
  });

  // Next проверяет редиректы РАНЬШЕ файловых маршрутов. Источник, совпавший
  // с живой страницей, делает её недоступной — и 308 браузер запоминает
  // навсегда, так что откат правки посетителя уже не вылечит.
  it("ни один источник не перекрывает живую страницу", async () => {
    for (const rule of await staticRedirects()) {
      // Источник пишется без слэша на конце, а Next добавляет к правилу
      // «(?:/)?$» — сравниваем обе формы.
      expect(routes.has(`${rule.source}/`), rule.source).toBe(false);
      expect(routes.has(rule.source), rule.source).toBe(false);
    }
  });

  // Один сайт — один адрес. `www.vedal-med.ru` отвечает тем же содержимым,
  // что и голый домен, и для поисковика это дубль.
  it("снимает www с любого хоста", async () => {
    const rule = (await redirects()).find((r) => r.has?.some((h) => h.type === "host"));

    expect(rule?.destination).toBe("https://:host/:path*");
    expect(rule?.has).toEqual([{ type: "host", value: "www\\.(?<host>.+)" }]);
  });

  // Русская версия стоит в корне, а не под префиксом: у `/ru/products/`
  // содержание не исчезло, поэтому и уводить его надо на страницу,
  // а не на главную.
  it("уводит /ru/… на ту же страницу без префикса", async () => {
    expect(await redirects()).toContainEqual({
      source: "/ru/:path*",
      destination: "/:path*/",
      permanent: true,
    });
  });
});

describe("заголовки кэширования", () => {
  const cacheRule = async () => {
    const all = (await config.headers?.()) ?? [];
    return all.find((rule) => rule.headers.some((h) => h.key === "Cache-Control"));
  };

  // Из-за `stale-while-revalidate` почти на год браузер отдавал вчерашний
  // HTML сразу, а обновлял его в фоне: заказчик утром видел старое меню,
  // а к обеду новое. Страница обязана спрашивать сервер каждый раз —
  // с ETag это ответ 304 без тела.
  it("не дают HTML залипнуть у посетителя", async () => {
    const value = (await cacheRule())?.headers.find((h) => h.key === "Cache-Control")?.value;

    expect(value).toBe("public, max-age=0, must-revalidate");
    expect(value).not.toMatch(/stale-while-revalidate/);
  });

  // Обратная опасность той же правки: заголовки next.config применяются
  // и к собранной статике тоже — `/_next` из них, в отличие от редиректов,
  // не исключается сам. Без исключения каждый чанк и каждый стиль
  // перезапрашивались бы на каждой странице.
  it("не трогают хэшированную статику Next", async () => {
    const rule = await cacheRule();

    expect(rule?.source).toContain("(?!_next/)");
  });
});
