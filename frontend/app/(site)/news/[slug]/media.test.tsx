import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

// Галерея и видео к новости (content/news-media.ts).
//
// Проверяется то, что ломается молча: видео, которое грузится у каждого
// открывшего страницу (два ролика по 8–10 МБ), и новость без галереи,
// у которой вдруг появился пустой блок.

const запись = (slug: string) => ({
  slug,
  isoDate: "2026-07-06",
  date: "6 июля 2026",
  tag: "Выставки",
  title: "Заголовок",
  excerpt: "Анонс",
  body: "Первый абзац.\n\nВторой абзац.",
  image: { src: "/photos/news/innoprom-2026/cover.jpg", alt: "Обложка" },
});

vi.mock("@/lib/api", () => ({
  fetchNewsEntry: async (slug: string) => запись(slug),
}));

describe("медиа новости", () => {
  it("у ИННОПРОМа — галерея и видео, которые не грузятся заранее", async () => {
    const { newsMedia } = await import("@/content/news-media");
    const { default: NewsEntryScreen } = await import("./screen");
    const { container } = render(await NewsEntryScreen({ slug: "innoprom-2026" }));

    const media = newsMedia["innoprom-2026"];
    for (const shot of media.gallery) {
      expect(screen.getByAltText(shot.alt)).toBeInTheDocument();
    }
    const videos = container.querySelectorAll("video");
    expect(videos).toHaveLength(media.videos.length);
    for (const [i, v] of [...videos].entries()) {
      expect(v).toHaveAttribute("preload", "none");
      expect(v).toHaveAttribute("controls");
      // Без обложки плеер с preload="none" — чёрный прямоугольник.
      expect(v.getAttribute("poster")).toContain(media.videos[i].poster);
    }
  });

  it("у анонса конгресса показана схема стенда В3", async () => {
    const { newsMedia } = await import("@/content/news-media");
    const { default: NewsEntryScreen } = await import("./screen");
    render(await NewsEntryScreen({ slug: "aran-2026" }));

    const [map] = newsMedia["aran-2026"].gallery;
    expect(newsMedia["aran-2026"].gallery).toHaveLength(1);
    expect(screen.getByAltText(map.alt)).toBeInTheDocument();
    expect(map.src).toBe("/photos/news/aran-2026/exhibition-map.jpg");
  });

  it("у новости без медиа нет ни галереи, ни плеера", async () => {
    const { default: NewsEntryScreen } = await import("./screen");
    const { container } = render(await NewsEntryScreen({ slug: "drugaya-novost" }));

    expect(container.querySelectorAll("video")).toHaveLength(0);
    expect(container.querySelectorAll("ul")).toHaveLength(0);
  });
});
