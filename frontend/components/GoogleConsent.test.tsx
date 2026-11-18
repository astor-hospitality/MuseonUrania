import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { cleanup, render } from "@testing-library/react";
import GoogleConsent from "./GoogleConsent";

const key = "vedal.google.analytics.v1";
const selector = 'script[src^="https://www.googletagmanager.com/gtag/js"]';
beforeEach(() => { localStorage.clear(); document.querySelectorAll(selector).forEach((el) => el.remove()); });
afterEach(() => { cleanup(); document.querySelectorAll(selector).forEach((el) => el.remove()); });

describe("Google analytics", () => {
  it("does not load without consent", () => {
    render(<GoogleConsent />);
    expect(document.querySelector(selector)).toBeNull();
  });
  it("does not load after refusal", () => {
    localStorage.setItem(key, "no");
    render(<GoogleConsent />);
    expect(document.querySelector(selector)).toBeNull();
  });
  it("loads after explicit consent", () => {
    localStorage.setItem(key, "yes");
    render(<GoogleConsent />);
    expect(document.querySelector(selector)).not.toBeNull();
  });
});
