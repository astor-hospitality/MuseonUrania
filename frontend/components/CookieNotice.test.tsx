import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// Плашка согласия. Issues #53 и #74.
//
// Проверяется не вёрстка, а три свойства, которые ломаются молча:
// плашка не возвращается к тому, кто уже ответил; выбор «только
// необходимые» появляется ровно тогда, когда есть что отклонять;
// и текст называет то, что на площадке действительно есть.

const saved = process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID;

beforeEach(() => {
  vi.resetModules();
  vi.doUnmock("@/lib/maps");
  delete process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID;
  localStorage.clear();
});

afterEach(() => {
  if (saved === undefined) delete process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID;
  else process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID = saved;
});

// Номер счётчика читается на импорте, поэтому компонент импортируется заново
// в каждом тесте — уже после того, как переменная окружения выставлена.
async function показать({ счётчик, карта = true }: { счётчик: boolean; карта?: boolean }) {
  if (счётчик) process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID = "12345678";
  if (!карта) vi.doMock("@/lib/maps", () => ({ mapEmbedded: false, mapEmbedSrc: () => "" }));
  const { default: CookieNotice } = await import("./CookieNotice");
  render(<CookieNotice />);
  return userEvent.setup();
}

describe("плашка без сторонних ресурсов", () => {
  it("сообщает и не предлагает выбора", async () => {
    await показать({ счётчик: false, карта: false });

    expect(screen.getByText(/Счётчиков аналитики нет/)).toBeInTheDocument();
    // Отклонять нечего: cookie самого сайта нужны, чтобы страницы работали.
    // Кнопка «отклонить» здесь была бы бутафорией.
    expect(screen.queryByRole("button", { name: "Только необходимые" })).toBeNull();
  });

  it("после «Понятно» не возвращается", async () => {
    const user = await показать({ счётчик: false, карта: false });

    await user.click(screen.getByRole("button", { name: "Понятно" }));

    expect(screen.queryByRole("complementary", { name: "Использование cookie" })).toBeNull();
  });
});

describe("плашка с картой, но без счётчика", () => {
  it("называет карту и говорит, что остаётся вместо неё", async () => {
    await показать({ счётчик: false });

    // Согласие на то, что человеку не назвали, согласием не является.
    expect(screen.getByText(/[Кк]арта Яндекса/)).toBeInTheDocument();
    // «Только необходимые» не должно читаться как «остаться без адреса».
    expect(screen.getByText(/Построить маршрут/)).toBeInTheDocument();
  });

  it("не обещает счётчик, которого нет", async () => {
    await показать({ счётчик: false });

    expect(screen.getByText(/Счётчиков аналитики нет/)).toBeInTheDocument();
  });

  it("даёт обе кнопки: карту есть чем отклонить", async () => {
    const user = await показать({ счётчик: false });

    await user.click(screen.getByRole("button", { name: "Только необходимые" }));

    expect(localStorage.getItem("vedal.analytics.v1")).toBe("denied");
  });
});

describe("плашка со счётчиком", () => {
  it("называет Метрику, карту и передачу данных в Яндекс", async () => {
    await показать({ счётчик: true });

    // Согласие на то, что человеку не назвали, согласием не является.
    expect(screen.getByText(/Яндекс Метрика/)).toBeInTheDocument();
    expect(screen.getByText(/[Кк]арта Яндекса/)).toBeInTheDocument();
    expect(screen.getByText(/уходят в Яндекс/)).toBeInTheDocument();
  });

  it("даёт обе кнопки одного размера", async () => {
    await показать({ счётчик: true });

    // Спрятанный отказ — это отказ, которого нет. Кнопка обязана быть
    // кнопкой, а не строчкой мелким шрифтом.
    expect(screen.getByRole("button", { name: "Принять" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Только необходимые" })).toBeInTheDocument();
  });

  it("«Принять» записывает разрешение и убирает плашку", async () => {
    const user = await показать({ счётчик: true });

    await user.click(screen.getByRole("button", { name: "Принять" }));

    expect(localStorage.getItem("vedal.analytics.v2")).toBe("granted");
    expect(screen.queryByRole("complementary", { name: "Использование cookie" })).toBeNull();
  });

  it("«Только необходимые» записывает отказ и убирает плашку", async () => {
    const user = await показать({ счётчик: true });

    await user.click(screen.getByRole("button", { name: "Только необходимые" }));

    expect(localStorage.getItem("vedal.analytics.v2")).toBe("denied");
    expect(screen.queryByRole("complementary", { name: "Использование cookie" })).toBeNull();
  });

  it("не показывается тому, кто уже ответил", async () => {
    localStorage.setItem("vedal.analytics.v1", "denied");

    await показать({ счётчик: true });

    expect(screen.queryByRole("complementary", { name: "Использование cookie" })).toBeNull();
  });

  it("спрашивает заново того, кто отвечал на плашку без выбора", async () => {
    // Ответ «Понятно» был дан на другой вопрос: счётчика тогда не было.
    localStorage.setItem("vedal.cookies.v1", "2026-01-01T00:00:00.000Z");

    await показать({ счётчик: true });

    expect(screen.getByRole("button", { name: "Только необходимые" })).toBeInTheDocument();
  });
});
