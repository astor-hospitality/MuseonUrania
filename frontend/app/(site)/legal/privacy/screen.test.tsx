import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { site } from "@/content/site";
import PrivacyScreen from "./screen";

// Политика обработки персональных данных.
//
// Проверяется не вёрстка, а то, что ломается молча и дорого. У юридического
// документа таких мест три: реквизиты, которые кто-нибудь однажды впишет
// строкой мимо `content/site.ts`; условные утверждения про Яндекс, которые
// на площадке без счётчика превращаются в неправду; и оглавление, пункт
// которого ведёт в никуда после переименования раздела.
//
// Отдельно — служебные пометки. Страница полтора месяца сообщала посетителю,
// что документа нет; вернуть такую строку в текст легко и незаметно.

afterEach(() => {
  vi.doUnmock("@/lib/maps");
});

/**
 * Политика, собранная под конкретную площадку.
 *
 * Счётчик и карта решают, что на странице написано про передачу данных
 * в Яндекс, а читаются они на импорте модуля — поэтому модули сбрасываются
 * и импортируются заново в каждом случае.
 */
async function политикаПлощадки({ счётчик, карта }: { счётчик: string; карта: boolean }) {
  vi.resetModules();
  vi.stubEnv("NEXT_PUBLIC_YANDEX_METRIKA_ID", счётчик);
  vi.doMock("@/lib/maps", () => ({
    mapEmbedded: карта,
    mapEmbedSrc: () => "",
    mapRouteHref: () => "",
  }));
  const { privacy } = await import("@/content/legal");
  return privacy;
}

const разделПро = (
  политика: Awaited<ReturnType<typeof политикаПлощадки>>,
  id: string,
): string => {
  const раздел = политика.sections.find((s) => s.id === id);
  expect(раздел, `раздел «${id}» пропал из политики`).toBeDefined();
  return [раздел?.text ?? "", ...(раздел?.items ?? [])].join("\n");
};

describe("политика обработки персональных данных", () => {
  it("показывает реквизиты оператора из content/site.ts", () => {
    render(<PrivacyScreen />);

    // Именно из site.ts, а не «такие же строки»: 8 сентября заказчик нашёл
    // на сайте ИНН, переписанный в графу КПП. Вторая копия реквизитов
    // расходится с первой молча.
    expect(screen.getByText(site.legalNameFull)).toBeInTheDocument();
    expect(screen.getByText(site.inn)).toBeInTheDocument();
    expect(screen.getByText(site.kpp)).toBeInTheDocument();
    expect(screen.getByText(site.address)).toBeInTheDocument();
    expect(screen.getAllByText(site.phone).length).toBeGreaterThan(0);
    expect(screen.getAllByText(site.email).length).toBeGreaterThan(0);
  });

  it("даёт позвонить и написать по контактам компании", () => {
    render(<PrivacyScreen />);

    expect(screen.getByRole("link", { name: site.phone })).toHaveAttribute(
      "href",
      `tel:${site.phone.replace(/\s/g, "")}`,
    );
    expect(screen.getByRole("link", { name: site.email })).toHaveAttribute(
      "href",
      `mailto:${site.email}`,
    );
  });

  it("не сообщает, что документ готовится", () => {
    const { container } = render(<PrivacyScreen />);
    const текст = container.textContent ?? "";

    // Страница и есть документ. Служебная отметка о нашем внутреннем процессе
    // отвечает человеку не на тот вопрос, с которым он сюда пришёл.
    expect(текст).not.toMatch(/ожидает уточнения/i);
    expect(текст).not.toMatch(/документ готовится/i);
    expect(текст).not.toMatch(/что войдёт в документ/i);
  });

  it("называет редакцию документа", () => {
    render(<PrivacyScreen />);

    // Без даты нельзя ответить на вопрос «под какой редакцией я подписался».
    expect(screen.getByText(/^Редакция от /)).toBeInTheDocument();
  });

  it("предлагает файл и называет его размер", () => {
    render(<PrivacyScreen />);

    const ссылка = screen.getByRole("link", { name: /Скачать PDF/ });
    expect(ссылка).toHaveAttribute("href", "/documents/vedal-privacy-policy.pdf");
    // Размер проставляет scripts/privacy-pdf.sh. Ноль означает, что файл
    // не пересобирали — то есть ссылка ведёт на прошлую редакцию текста.
    expect(ссылка).toHaveTextContent(/\d+ КБ/);
    expect(ссылка).not.toHaveTextContent(/(^|\D)0 КБ/);
  });

  it("каждый пункт оглавления ведёт на существующий раздел", () => {
    const { container } = render(<PrivacyScreen />);

    const пункты = Array.from(
      container.querySelectorAll<HTMLAnchorElement>("nav a[href^='#']"),
    );
    expect(пункты.length).toBeGreaterThan(5);

    for (const пункт of пункты) {
      const якорь = пункт.getAttribute("href")?.slice(1) ?? "";
      expect(container.querySelector(`#${якорь}`), `оглавление ведёт в никуда: #${якорь}`)
        .not.toBeNull();
    }

    // Обратная сторона того же: раздел без пункта в оглавлении не найти,
    // а документ читают именно через оглавление.
    const заголовки = Array.from(container.querySelectorAll("section > h2[id]"));
    expect(пункты.length).toBe(заголовки.length);
  });
});

describe("политика про передачу данных в Яндекс", () => {
  it("на площадке со счётчиком и картой называет обе стороны", async () => {
    const политика = await политикаПлощадки({ счётчик: "12345678", карта: true });
    const cookie = разделПро(политика, "cookie");

    expect(cookie).toMatch(/Яндекс Метрики/);
    expect(cookie).toMatch(/карта Яндекса/);
    // Согласие в плашке — условие загрузки, а не любезность.
    expect(cookie).toMatch(/после согласия/);
  });

  it("без счётчика Яндекса корректно описывает Google", async () => {
    const политика = await политикаПлощадки({ счётчик: "", карта: true });
    const cookie = разделПро(политика, "cookie");

    expect(cookie).toMatch(/Счётчик Яндекс Метрики на публичных страницах не подключён/);
    expect(cookie).toMatch(/Google Analytics подключается только после отдельного согласия/);
  });

  it("без карты не рассказывает про карту", async () => {
    const политика = await политикаПлощадки({ счётчик: "12345678", карта: false });
    const cookie = разделПро(политика, "cookie");

    expect(cookie).not.toMatch(/карт/i);
    expect(cookie).toMatch(/Яндекс Метрики/);
  });
});
