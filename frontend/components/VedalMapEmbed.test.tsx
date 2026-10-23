import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// Встроенная карта Яндекса. Issue #74.
//
// Проверяется одно свойство, и оно ломается молча: кадра нет в разметке,
// пока человек не согласился. Ошибка здесь ничего не рушит и ничем себя
// не выдаёт — карта просто начинает грузиться у того, кто её не разрешал,
// а вместе с ней в Яндекс уходит его визит.

const saved = process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID;

beforeEach(() => {
  vi.resetModules();
  delete process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID;
  localStorage.clear();
});

afterEach(() => {
  if (saved === undefined) delete process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID;
  else process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID = saved;
});

const SRC = "https://yandex.ru/map-widget/v1/?text=%D0%B0%D0%B4%D1%80%D0%B5%D1%81&z=17";

// Модули читают согласие и номер счётчика на импорте, поэтому компонент
// импортируется заново в каждом тесте — после того, как localStorage заполнен.
async function показать() {
  const { default: VedalMapEmbed } = await import("./VedalMapEmbed");
  render(
    <VedalMapEmbed src={SRC} title="Карта: адрес">
      <p>Схема проезда</p>
    </VedalMapEmbed>,
  );
}

const кадр = () => screen.queryByTitle("Карта: адрес");

describe("встроенная карта", () => {
  it("до ответа кадра нет, а запасной вариант на месте", async () => {
    await показать();

    expect(кадр()).toBeNull();
    expect(screen.getByText("Схема проезда")).toBeInTheDocument();
  });

  it("после отказа кадра нет", async () => {
    localStorage.setItem("vedal.analytics.v1", "denied");

    await показать();

    expect(кадр()).toBeNull();
    expect(screen.getByText("Схема проезда")).toBeInTheDocument();
  });

  it("после согласия грузит карту вместо запасного варианта", async () => {
    localStorage.setItem("vedal.analytics.v1", "granted");

    await показать();

    expect(кадр()).toHaveAttribute("src", SRC);
    expect(screen.queryByText("Схема проезда")).toBeNull();
  });

  it("при недоступном хранилище кадра нет", async () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("приватный режим");
    });

    await показать();

    // Молчание согласием не считается: спросить некуда, значит и грузить
    // нечего. Посетитель остаётся со схемой и ссылкой на маршрут.
    expect(кадр()).toBeNull();
  });

  it("согласие на счётчик разрешает и карту", async () => {
    // Вопрос в плашке один на оба ресурса: человек отвечает на то, уйдут
    // ли данные о визите в Яндекс, а не на «счётчик отдельно, карта
    // отдельно». Разъедься это — второй вопрос пришлось бы задать, а его
    // в плашке нет.
    process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID = "12345678";
    localStorage.setItem("vedal.analytics.v2", "granted");

    await показать();

    expect(кадр()).toBeInTheDocument();
  });
});
