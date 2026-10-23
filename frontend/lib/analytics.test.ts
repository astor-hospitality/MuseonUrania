import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// Счётчик ломается в обе стороны молча. Не подключился — отчёты пусты,
// и понять это можно только заглянув в них через неделю. Подключился там,
// где не должен, — данные посетителей уехали третьей стороне без согласия,
// и понять это нельзя вообще ничем, кроме кода.
describe("Яндекс Метрика", () => {
  const saved = process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID;

  beforeEach(() => {
    vi.resetModules();
    delete process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID;
  });

  afterEach(() => {
    if (saved === undefined) delete process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID;
    else process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID = saved;
    document.head.innerHTML = "";
    delete (window as { ym?: unknown }).ym;
  });

  describe("номер счётчика", () => {
    it("без переменной окружения счётчика нет", async () => {
      const { metrikaId } = await import("./analytics");

      // Не «поломка, которую надо чинить», а рабочее состояние стенда
      // и машины разработчика: их визиты не должны попадать в отчёты
      // боевого сайта.
      expect(metrikaId).toBeNull();
    });

    it("берёт номер из NEXT_PUBLIC_YANDEX_METRIKA_ID", async () => {
      process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID = "12345678";

      const { metrikaId } = await import("./analytics");

      expect(metrikaId).toBe("12345678");
    });

    it("нечисловое значение считает отсутствием счётчика и предупреждает", async () => {
      process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID = "CID-12345";
      const warn = vi.spyOn(console, "warn").mockImplementation(() => {});

      const { metrikaId } = await import("./analytics");

      // Метрика на такой номер не ругается — она просто молчит. Молчащий
      // счётчик неотличим от выключенного, поэтому опечатку ловим здесь.
      expect(metrikaId).toBeNull();
      expect(warn).toHaveBeenCalledOnce();
    });

    it("пустую строку не путает с номером и не предупреждает", async () => {
      process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID = "   ";
      const warn = vi.spyOn(console, "warn").mockImplementation(() => {});

      const { metrikaId } = await import("./analytics");

      expect(metrikaId).toBeNull();
      expect(warn).not.toHaveBeenCalled();
    });
  });

  describe("подключение", () => {
    it("заводит очередь вызовов и грузит tag.js", async () => {
      process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID = "12345678";
      const { installMetrika } = await import("./analytics");

      installMetrika("12345678");

      const tag = document.querySelector<HTMLScriptElement>(
        'script[src="https://mc.yandex.ru/metrika/tag.js"]',
      );
      expect(tag).not.toBeNull();
      expect(tag?.async).toBe(true);

      // Вызовы, сделанные до загрузки tag.js, копятся в очереди. Без неё
      // первый же init упал бы на «ym is not a function».
      const ym = (window as { ym?: { a?: unknown[][] } }).ym;
      expect(ym?.a?.[0]?.[1]).toBe("init");
    });

    it("вебвизор включён после согласия v2", async () => {
      process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID = "12345678";
      const { installMetrika } = await import("./analytics");

      installMetrika("12345678");

      const init = (window as { ym?: { a?: unknown[][] } }).ym?.a?.[0];
      expect((init?.[2] as { webvisor?: boolean }).webvisor).toBe(true);
    });

    it("повторный вызов не ставит второй счётчик", async () => {
      process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID = "12345678";
      const { installMetrika } = await import("./analytics");

      // React в разработке монтирует компоненты дважды. Два тега — два
      // счётчика и удвоенная посещаемость в отчётах.
      installMetrika("12345678");
      installMetrika("12345678");

      expect(
        document.querySelectorAll('script[src="https://mc.yandex.ru/metrika/tag.js"]'),
      ).toHaveLength(1);
    });
  });

  describe("цели", () => {
    it("без счётчика молчит", async () => {
      const { reachGoal } = await import("./analytics");
      const ym = vi.fn();
      (window as { ym?: unknown }).ym = ym;

      reachGoal("quote_form_submit");

      // Счётчика нет — целям некуда идти, и вызов не должен ни падать,
      // ни отправлять что-либо, если ym остался от чего-то другого.
      expect(ym).not.toHaveBeenCalled();
    });

    it("без загруженного tag.js не падает", async () => {
      process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID = "12345678";
      const { reachGoal } = await import("./analytics");

      expect(() => reachGoal("quote_form_submit")).not.toThrow();
    });

    it("отправляет цель в счётчик", async () => {
      process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID = "12345678";
      const { reachGoal } = await import("./analytics");
      const ym = vi.fn();
      (window as { ym?: unknown }).ym = ym;

      reachGoal("quote_form_submit");

      expect(ym).toHaveBeenCalledWith("12345678", "reachGoal", "quote_form_submit");
    });

    it("считает просмотр страницы при переходе внутри сайта", async () => {
      process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID = "12345678";
      const { trackPageView } = await import("./analytics");
      const ym = vi.fn();
      (window as { ym?: unknown }).ym = ym;

      trackPageView("/products/vedal-r1/");

      // Next меняет страницы без перезагрузки: без этого вызова в отчётах
      // остались бы одни точки входа.
      expect(ym).toHaveBeenCalledWith("12345678", "hit", "/products/vedal-r1/");
    });
  });
});
