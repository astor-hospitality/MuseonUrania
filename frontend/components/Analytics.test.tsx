import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// Счётчик и цели. Issue #53.
//
// Главное здесь — первый describe. Счётчик, поднявшийся до согласия, — это
// передача данных посетителя третьей стороне без разрешения; страница при
// этом выглядит совершенно нормально, и заметить такое можно только тестом
// или чтением кода.

vi.mock("next/navigation", () => ({ usePathname: () => "/" }));

const TAG = 'script[src="https://mc.yandex.ru/metrika/tag.js"]';
const saved = process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID;

beforeEach(() => {
  vi.resetModules();
  delete process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID;
  localStorage.clear();
  document.head.innerHTML = "";
  delete (window as { ym?: unknown }).ym;
});

afterEach(() => {
  if (saved === undefined) delete process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID;
  else process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID = saved;
});

async function смонтировать({ счётчик }: { счётчик: boolean }) {
  if (счётчик) process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID = "12345678";
  const { default: Analytics } = await import("./Analytics");
  await act(async () => {
    render(
      <>
        <Analytics />
        <button type="button" data-analytics="hero_quote_click">
          Запросить КП
        </button>
        {/* Якорь, а не путь к файлу: переход по настоящему адресу jsdom
            не умеет и шумит в вывод теста, а проверяем мы не переход. */}
        <a href="#datasheet" data-analytics="document_download_click">
          <span>Датащит</span>
        </a>
      </>,
    );
  });
  return userEvent.setup();
}

const разрешить = () => localStorage.setItem("vedal.analytics.v2", "granted");

describe("счётчик не поднимается без права", () => {
  it("нет согласия — нет скрипта", async () => {
    await смонтировать({ счётчик: true });

    expect(document.querySelector(TAG)).toBeNull();
  });

  it("отказ — нет скрипта", async () => {
    localStorage.setItem("vedal.analytics.v1", "denied");

    await смонтировать({ счётчик: true });

    expect(document.querySelector(TAG)).toBeNull();
  });

  it("согласие есть, а номера счётчика нет — нет скрипта", async () => {
    разрешить();

    await смонтировать({ счётчик: false });

    // Пустая переменная — рабочее состояние стенда и машины разработчика.
    expect(document.querySelector(TAG)).toBeNull();
  });

  it("номер и согласие вместе — скрипт грузится", async () => {
    разрешить();

    await смонтировать({ счётчик: true });

    expect(document.querySelector(TAG)).not.toBeNull();
  });
});

describe("цели из чек-листа приёмки", () => {
  it("клик по размеченной кнопке уходит целью", async () => {
    разрешить();
    const user = await смонтировать({ счётчик: true });
    const ym = vi.fn();
    (window as { ym?: unknown }).ym = ym;

    await user.click(screen.getByRole("button", { name: "Запросить КП" }));

    expect(ym).toHaveBeenCalledWith("12345678", "reachGoal", "hero_quote_click");
  });

  it("клик по вложенному узлу засчитывается родителю", async () => {
    разрешить();
    const user = await смонтировать({ счётчик: true });
    const ym = vi.fn();
    (window as { ym?: unknown }).ym = ym;

    // В перечне документов кликают по названию внутри ссылки, а атрибут
    // стоит на самой ссылке. Без closest цель терялась бы у половины кликов.
    await user.click(screen.getByText("Датащит"));

    expect(ym).toHaveBeenCalledWith("12345678", "reachGoal", "document_download_click");
  });

  it("без согласия клик не уходит никуда", async () => {
    const user = await смонтировать({ счётчик: true });
    const ym = vi.fn();
    (window as { ym?: unknown }).ym = ym;

    await user.click(screen.getByRole("button", { name: "Запросить КП" }));

    expect(ym).not.toHaveBeenCalled();
  });
});
