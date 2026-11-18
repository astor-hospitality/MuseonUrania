import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import GoogleConsent from "./GoogleConsent";

const key = "vedal.google.analytics.v1";
const selector = 'script[src^="https://www.googletagmanager.com/gtag/js"]';
beforeEach(() => { localStorage.clear(); document.querySelectorAll(selector).forEach((el) => el.remove()); });
afterEach(() => { cleanup(); document.querySelectorAll(selector).forEach((el) => el.remove()); vi.restoreAllMocks(); });

describe("GoogleConsent", () => {
  it("does not load Google until the visitor explicitly accepts", async () => {
    render(<GoogleConsent />);
    expect(document.querySelector(selector)).toBeNull();
    fireEvent.click(screen.getByRole("button", {name: "Google Analytics"}));
    fireEvent.click(screen.getByRole("button", {name: "Разрешить Google"}));
    expect(localStorage.getItem(key)).toBe("yes");
    expect(document.querySelector(selector)).not.toBeNull();
  });
  it("does not load Google after a stored refusal", async () => {
    localStorage.setItem(key, "no");
    render(<GoogleConsent />);
    await waitFor(() => expect(screen.getByRole("button", {name:"Google Analytics"})).toBeTruthy());
    expect(document.querySelector(selector)).toBeNull();
    expect(screen.queryByRole("dialog")).toBeNull();
  });
});
