import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

beforeEach(() => { vi.resetModules(); localStorage.clear(); });

describe("единая плашка cookie", () => {
  it("показывает одну кнопку и после явного согласия сохраняет оба ответа", async () => {
    process.env.NEXT_PUBLIC_YANDEX_METRIKA_ID = "12345678";
    const { default: CookieNotice } = await import("./CookieNotice");
    render(<CookieNotice />);
    const notice = screen.getByRole("complementary", { name: "Использование cookie" });
    expect(notice).toHaveTextContent("Google Analytics");
    expect(notice).toHaveTextContent("Яндекс Метрика");
    expect(screen.getAllByRole("button")).toHaveLength(1);
    expect(localStorage.getItem("vedal.google.analytics.v1")).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "Принять" }));
    expect(localStorage.getItem("vedal.google.analytics.v1")).toBe("yes");
    expect(localStorage.getItem("vedal.analytics.v2")).toBe("granted");
    expect(screen.queryByRole("complementary")).toBeNull();
  });

  it("предыдущий отказ не включает Google без нового согласия", async () => {
    localStorage.setItem("vedal.analytics.v2", "denied");
    const { default: CookieNotice } = await import("./CookieNotice");
    render(<CookieNotice />);
    expect(screen.getByRole("button", { name: "Принять" })).toBeInTheDocument();
    expect(localStorage.getItem("vedal.google.analytics.v1")).toBeNull();
  });
});
