import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// Согласие — единственное, что стоит между посетителем и передачей данных
// в Яндекс. Ошибка здесь не ломает страницу и ничем себя не выдаёт: счётчик
// и карта просто начинают работать у того, кто их не разрешал.
describe("согласие на сторонние ресурсы Яндекса", () => {
  const saved = process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID;

  beforeEach(() => {
    vi.resetModules();
    vi.doUnmock("./maps");
    delete process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID;
    localStorage.clear();
  });

  afterEach(() => {
    if (saved === undefined) delete process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID;
    else process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID = saved;
  });

  // Номер счётчика читается на импорте модуля, поэтому переменную окружения
  // ставим до него, а сам импорт делаем заново в каждом тесте.
  const isolated = async () => {
    process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID = "12345678";
    return import("./consent");
  };

  // Карта стоит на контактах всегда, и снимается она правкой одной строки
  // в lib/maps.ts. Ветка «спрашивать не о чем» существует ради этой правки —
  // здесь она и проверяется.
  const безСторонних = async () => {
    vi.doMock("./maps", () => ({ mapEmbedded: false }));
    return import("./consent");
  };

  describe("площадка без сторонних ресурсов", () => {
    it("до ответа плашку показывает", async () => {
      const { readConsent } = await безСторонних();

      expect(readConsent()).toBe("unanswered");
    });

    it("после «Понятно» плашку больше не показывает", async () => {
      const { acknowledge, readConsent } = await безСторонних();

      acknowledge();

      expect(readConsent()).toBe("necessary");
    });

    it("«Понятно» не записывает ответ про сторонние ресурсы", async () => {
      const { acknowledge } = await безСторонних();

      acknowledge();

      // Записать сюда «granted» значило бы включить счётчик и карту тому,
      // кого о них не спрашивали.
      expect(localStorage.getItem("vedal.analytics.v1")).toBeNull();
    });
  });

  describe("площадка с картой, но без счётчика", () => {
    it("спрашивает: разрешать есть что", async () => {
      // Счётчика нет, а кадр Яндекс.Карт на контактах есть, и он такая же
      // передача данных третьей стороне. Молчать про него нельзя.
      const { asksThirdParty, readConsent } = await import("./consent");

      expect(asksThirdParty).toBe(true);
      expect(readConsent()).toBe("unanswered");
    });

    it("старое «Понятно» ответом не считается", async () => {
      // «Понятно» было дано на плашку без выбора, когда сторонних ресурсов
      // не было вовсе. Карта появилась позже, и вопрос задаётся заново.
      localStorage.setItem("vedal.cookies.v1", "2026-01-01T00:00:00.000Z");
      const { readConsent } = await import("./consent");

      expect(readConsent()).toBe("unanswered");
    });

    it("«Принять» разрешает карту", async () => {
      const { answer, readConsent } = await import("./consent");

      answer(true);

      expect(readConsent()).toBe("analytics");
    });

    it("«Только необходимые» карту не разрешает", async () => {
      const { answer, readConsent } = await import("./consent");

      answer(false);

      expect(readConsent()).toBe("necessary");
    });
  });

  describe("площадка со счётчиком", () => {
    it("не переносит старое согласие на запись действий", async () => {
      localStorage.setItem("vedal.analytics.v1", "granted");
      const { readConsent } = await isolated();
      expect(readConsent()).toBe("unanswered");
    });

    it("сохраняет прежний отказ без повторного запроса", async () => {
      localStorage.setItem("vedal.analytics.v1", "denied");
      const { readConsent } = await isolated();
      expect(readConsent()).toBe("necessary");
    });
    it("до ответа не считается согласием", async () => {
      const { readConsent } = await isolated();

      expect(readConsent()).toBe("unanswered");
    });

    it("старое «Понятно» ответом про аналитику не считается", async () => {
      // Человек нажал «Понятно» на плашке, где выбора не было: тогда
      // передавать было нечего и некому. Счётчик появился позже, и вопрос
      // ему обязаны задать заново.
      localStorage.setItem("vedal.cookies.v1", "2026-01-01T00:00:00.000Z");
      const { readConsent } = await isolated();

      expect(readConsent()).toBe("unanswered");
    });

    it("«Принять» разрешает счётчик", async () => {
      const { answer, readConsent } = await isolated();

      answer(true);

      expect(readConsent()).toBe("analytics");
    });

    it("«Только необходимые» счётчик не разрешает", async () => {
      const { answer, readConsent } = await isolated();

      answer(false);

      expect(readConsent()).toBe("necessary");
    });

    it("отказ переживает возврат на сайт", async () => {
      const { answer } = await isolated();
      answer(false);

      vi.resetModules();
      const { readConsent } = await isolated();

      expect(readConsent()).toBe("necessary");
    });
  });

  it("недоступное хранилище согласием не считает", async () => {
    const { readConsent } = await isolated();
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("приватный режим");
    });

    // Плашки в этом режиме нет — ответ сохранить некуда, и она возвращалась
    // бы на каждой странице. Но молчание согласием не становится: «unknown»
    // счётчик не поднимает.
    expect(readConsent()).toBe("unknown");
  });

  it("будит подписчиков в своей же вкладке", async () => {
    const { answer, subscribe } = await isolated();
    const notify = vi.fn();
    subscribe(notify);

    answer(true);

    // Событие storage в своей вкладке не срабатывает: без собственного
    // оповещения нажатие «Принять» не убрало бы плашку и не подняло счётчик.
    expect(notify).toHaveBeenCalled();
  });
});
