import { describe, expect, it } from "vitest";

import { contentSecurityPolicy, metrikaReplayRoutes, securityHeaders } from "./security-headers";

// Политику содержимого можно сломать молча в обе стороны, и обе одинаково
// плохи. Слишком строгая — страница перестаёт грузить шрифт, картинку
// из хранилища или кадр карты, и человек видит пустое место без единой
// ошибки на экране. Слишком слабая — заголовок есть, выглядит внушительно
// и не защищает ни от чего.
//
// До 8 сентября политика жила только в конфигурации Caddy, а на стенде
// домен держит nginx, и Caddy в цепочке нет вовсе: заголовка у страниц
// не было. Проверялась она `caddy validate` — то есть в конфигурации,
// которая на стенде не применяется. Проверка была зелёной, защита
// не работала. Отсюда правило: проверяем там же, где собирается.

const ПУСТО = {
  NEXT_PUBLIC_MEDIA_URL: "",
  NEXT_PUBLIC_API_URL: "",
  NEXT_PUBLIC_OIDC_ISSUER: "",
  NEXT_PUBLIC_YANDEX_METRIKA_ID: "",
};

describe("политика содержимого", () => {
  // Границы, которые и составляют смысл политики: чужой скрипт не выполнится,
  // страницу нельзя открыть в чужом кадре, форму нельзя отправить наружу.
  it("держит границы даже без единой настроенной площадки", () => {
    const csp = contentSecurityPolicy(ПУСТО);

    expect(csp).toContain("default-src 'self'");
    expect(csp).toContain("object-src 'none'");
    expect(csp).toContain("frame-ancestors 'none'");
    expect(csp).toContain("base-uri 'self'");
    expect(csp).toContain("form-action 'self'");
  });

  // Хранилище, портал и Keycloak живут на разных адресах локально и на одном
  // домене в облаке. Захардкоженный список сломал бы одно из двух.
  it("пускает ровно те площадки, что заданы окружением", () => {
    const csp = contentSecurityPolicy({
      ...ПУСТО,
      NEXT_PUBLIC_MEDIA_URL: "https://media.example.test/vedal-media",
      NEXT_PUBLIC_API_URL: "https://api.example.test",
      NEXT_PUBLIC_OIDC_ISSUER: "https://auth.example.test/realms/vedal",
    });

    expect(csp).toContain("img-src 'self' data: blob: https://media.example.test");
    expect(csp).toContain("connect-src 'self' https://api.example.test https://auth.example.test");
    expect(csp).toContain("form-action 'self' https://auth.example.test");
  });

  // Путь внутри адреса в политику не попадает: она про источник целиком,
  // и «https://media.example.test/vedal-media» в ней — ошибка, а не уточнение.
  it("берёт источник, а не путь внутри него", () => {
    const csp = contentSecurityPolicy({
      ...ПУСТО,
      NEXT_PUBLIC_MEDIA_URL: "https://media.example.test/vedal-media",
      NEXT_PUBLIC_OIDC_ISSUER: "https://auth.example.test/realms/vedal",
    });

    expect(csp).not.toContain("/vedal-media");
    expect(csp).not.toContain("/realms/vedal");
  });

  // Счётчика нет — и адресов счётчика в политике нет. Иначе она разрешала бы
  // то, чего на площадке не бывает, а это ровно то ослабление, которое
  // никто потом не заметит.
  it("не пускает счётчик, пока он не включён", () => {
    expect(contentSecurityPolicy(ПУСТО)).not.toContain("mc.yandex.ru");
  });

  it("пускает счётчик, когда он включён", () => {
    const csp = contentSecurityPolicy({ ...ПУСТО, NEXT_PUBLIC_YANDEX_METRIKA_ID: "12345678" });

    expect(csp).toContain("script-src 'self' 'unsafe-inline' https://mc.yandex.ru");
    expect(csp).toContain("img-src 'self' data: blob: https://mc.yandex.ru");
    expect(csp).toContain("connect-src 'self' https://mc.yandex.ru");
    expect(csp).toContain("https://mc.webvisor.org");
    expect(csp).toContain("wss://mc.webvisor.com");
    expect(csp).toContain("child-src 'self' blob:");
    expect(csp).toContain("frame-ancestors 'none'");
  });

  // Кадр карты разрешён всегда, а грузить его или нет — решает согласие
  // посетителя в самой странице. Разрешение без загрузки не отправляет
  // в Яндекс ничего.
  it("разрешает кадр карты независимо от счётчика", () => {
    expect(contentSecurityPolicy(ПУСТО)).toContain(
      "frame-src 'self' https://yandex.ru https://yandex.com",
    );
  });

  // Опечатка в необязательной переменной не должна ронять сборку сайта:
  // цена выше пользы. Политика просто становится строже на один источник.
  it("переживает кривой адрес в окружении", () => {
    const csp = contentSecurityPolicy({ ...ПУСТО, NEXT_PUBLIC_MEDIA_URL: "не адрес" });

    expect(csp).toContain("img-src 'self' data: blob:;");
  });
});

describe("набор заголовков", () => {
  it("разрешает только Метрике встраивать явно публичные страницы при включённом счётчике", () => {
    const env = { ...ПУСТО, NEXT_PUBLIC_YANDEX_METRIKA_ID: "112490942" };
    expect(contentSecurityPolicy(env)).toContain("frame-ancestors 'none'");
    expect(contentSecurityPolicy(ПУСТО, true)).toContain("frame-ancestors 'none'");
    expect(contentSecurityPolicy(env, true)).toContain(
      "frame-ancestors https://metrika.yandex.ru https://metrika.yandex.com",
    );
    expect(securityHeaders(env, true)).toContainEqual({ key: "X-Frame-Options", value: "DENY" });
    expect(metrikaReplayRoutes).toEqual([
      "/", "/about/:path*", "/production/:path*", "/products/:path*", "/service/:path*",
      "/news/:path*", "/contacts/:path*", "/legal/privacy/:path*",
    ]);
  });
  it("отдаёт все четыре", () => {
    expect(securityHeaders(ПУСТО).map((h) => h.key)).toEqual([
      "Content-Security-Policy",
      "X-Content-Type-Options",
      "X-Frame-Options",
      "Referrer-Policy",
    ]);
  });
});
